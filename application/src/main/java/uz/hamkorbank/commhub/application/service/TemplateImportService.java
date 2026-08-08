package uz.hamkorbank.commhub.application.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.hamkorbank.commhub.application.dto.TemplateImportResult;
import uz.hamkorbank.commhub.application.port.in.ImportTemplates;
import uz.hamkorbank.commhub.application.port.in.command.ImportTemplatesCommand;
import uz.hamkorbank.commhub.application.port.out.ClockPort;
import uz.hamkorbank.commhub.application.port.out.TemplateRepository;
import uz.hamkorbank.commhub.application.service.support.ConfigAuditor;
import uz.hamkorbank.commhub.domain.exception.DomainValidationException;
import uz.hamkorbank.commhub.domain.model.Template;
import uz.hamkorbank.commhub.domain.model.TemplateVersion;
import uz.hamkorbank.commhub.domain.model.vo.TemplateCode;
import uz.hamkorbank.commhub.domain.model.vo.TemplateId;
import uz.hamkorbank.commhub.domain.model.vo.TemplateVersionId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Import of the Bank's existing template base (~470 templates, FR-4.6).
 *
 * <p>Rows go in through the aggregate, never straight into SQL: merge-field syntax, version numbering per
 * locale, one published version per locale and maker/checker are all domain rules, and a migration that
 * skipped them would leave rows the admin panel then refuses to load.
 *
 * <p>Idempotent by wording: a row whose text is already the published version of its locale is skipped, so
 * the same file can be re-run after a few rows were fixed. Rows are grouped by code so that the three
 * locales of one template become three versions of one card rather than three cards.
 *
 * <p>A row the domain refuses is reported and the rest of the file still goes in — 470 rows converted by
 * hand will contain a few bad ones, and failing the whole import on the first of them means finding them one
 * per run. Validation failures cannot poison the transaction because they are raised by the factories,
 * before anything is written; an infrastructure failure still aborts the whole import, which for a migration
 * is the right outcome.
 */
@Service
public class TemplateImportService implements ImportTemplates {

    private final TemplateRepository templates;
    private final ClockPort clock;
    private final ConfigAuditor auditor;

    public TemplateImportService(TemplateRepository templates, ClockPort clock, ConfigAuditor auditor) {
        this.templates = Guard.notNull(templates, "templates");
        this.clock = Guard.notNull(clock, "clock");
        this.auditor = Guard.notNull(auditor, "auditor");
    }

    @Override
    @Transactional
    public TemplateImportResult importTemplates(ImportTemplatesCommand command) {
        Guard.notNull(command, "command");
        Counters counters = new Counters();
        List<TemplateImportResult.Failure> failures = new ArrayList<>();
        groupByCode(command.rows()).forEach((code, rows) -> {
            try {
                importOne(command, code, rows, counters);
            } catch (DomainValidationException e) {
                rows.forEach(row -> failures.add(new TemplateImportResult.Failure(
                        row.code(), row.locale().name(), e.getMessage())));
            }
        });
        TemplateImportResult result =
                new TemplateImportResult(counters.created, counters.imported, counters.skipped, failures);
        auditor.record(
                command.actor(),
                "template.import",
                "template",
                "catalogue",
                null,
                "created=%d, imported=%d, skipped=%d, failed=%d, published=%s"
                        .formatted(
                                result.created(),
                                result.imported(),
                                result.skipped(),
                                failures.size(),
                                command.publishes()));
        return result;
    }

    /** All rows of one code, in file order: the locales of one template belong to one card (FR-4.1). */
    private static Map<String, List<ImportTemplatesCommand.Row>> groupByCode(List<ImportTemplatesCommand.Row> rows) {
        Map<String, List<ImportTemplatesCommand.Row>> grouped = new LinkedHashMap<>();
        rows.forEach(row ->
                grouped.computeIfAbsent(row.code(), key -> new ArrayList<>()).add(row));
        return grouped;
    }

    private void importOne(
            ImportTemplatesCommand command, String code, List<ImportTemplatesCommand.Row> rows, Counters counters) {
        ImportTemplatesCommand.Row first = rows.getFirst();
        TemplateCode templateCode = TemplateCode.of(code);
        Optional<Template> existing = templates.findByCode(templateCode);
        Template template = existing.orElseGet(() ->
                Template.create(TemplateId.newId(), templateCode, first.channel(), first.direction(), first.owner()));
        if (existing.isEmpty()) {
            counters.created++;
        } else {
            Guard.isTrue(
                    template.channel() == first.channel(),
                    "template %s already exists on channel %s, the file says %s"
                            .formatted(code, template.channel(), first.channel()));
        }
        rows.forEach(row -> importVersion(command, template, row, counters));
        templates.save(template);
    }

    private void importVersion(
            ImportTemplatesCommand command, Template template, ImportTemplatesCommand.Row row, Counters counters) {
        TemplateVersion.Body body = new TemplateVersion.Body(row.subject(), row.text());
        if (alreadyPublished(template, row, body)) {
            counters.skipped++;
            return;
        }
        TemplateVersion version = TemplateVersion.draft(
                TemplateVersionId.newId(),
                template.id(),
                template.nextVersionNumber(row.locale()),
                row.locale(),
                body,
                command.author());
        template.addVersion(version);
        if (command.publishes()) {
            version.submitForReview();
            template.publishVersion(version, command.approver(), clock.now());
        }
        counters.imported++;
    }

    /** Whether the wording of the row is already what the locale sends (FR-4.6). */
    private static boolean alreadyPublished(
            Template template, ImportTemplatesCommand.Row row, TemplateVersion.Body body) {
        return template.publishedVersion(row.locale())
                .map(published -> published.body().equals(body))
                .orElse(false);
    }

    /** Per-row tallies of one import pass; the result reports them (FR-4.6). */
    private static final class Counters {
        private int created;
        private int imported;
        private int skipped;
    }
}
