package uz.hamkorbank.commhub.adapter.in.importer;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import uz.hamkorbank.commhub.application.dto.TemplateImportResult;
import uz.hamkorbank.commhub.application.port.in.ImportTemplates;
import uz.hamkorbank.commhub.application.port.in.command.ImportTemplatesCommand;
import uz.hamkorbank.commhub.domain.model.Actor;

/**
 * Runs the one-off import of the Bank's template base at start-up (FR-4.6).
 *
 * <p>Behind {@code commhub.import.templates.enabled}, off by default: this is a step of the roll-out, run as
 * a job with the file mounted, not something a pod restart should redo. Redoing it would in fact be harmless
 * — the use case skips wordings it already published — and that is the safety net, not the plan.
 *
 * <p>A failure is logged and does not stop the application. The Hub's job is to move the Bank's messages; a
 * malformed template file must not be the reason it will not start. The result of the run is in the log and
 * in the audit trail (FR-7.3).
 */
@Component
@ConditionalOnProperty(prefix = "commhub.import.templates", name = "enabled", havingValue = "true")
public class TemplateImportRunner implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(TemplateImportRunner.class);

    private final ImportTemplates importTemplates;
    private final TemplateCsvCodec codec;
    private final TemplateImportProperties properties;

    public TemplateImportRunner(
            ImportTemplates importTemplates, TemplateCsvCodec codec, TemplateImportProperties properties) {
        this.importTemplates = importTemplates;
        this.codec = codec;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            report(importFile());
        } catch (IOException | RuntimeException e) {
            LOG.error("Template import from {} failed; nothing was imported (FR-4.6)", properties.file(), e);
        }
    }

    private TemplateImportResult importFile() throws IOException {
        if (properties.file() == null || properties.file().isBlank()) {
            throw new TemplateImportException("commhub.import.templates.file is not set");
        }
        Path path = Path.of(properties.file());
        LOG.info(
                "Importing templates from {} as author={} approver={}",
                path,
                properties.author(),
                properties.approverOptional().orElse("<none, imported as drafts>"));
        TemplateCsvCodec.Parsed parsed;
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            parsed = codec.parse(reader, properties.separator());
        }
        if (parsed.rows().isEmpty()) {
            return new TemplateImportResult(0, 0, 0, parsed.failures());
        }
        TemplateImportResult result = importTemplates.importTemplates(new ImportTemplatesCommand(
                Actor.operator(properties.author()), properties.author(), properties.approver(), parsed.rows()));
        return merge(result, parsed.failures());
    }

    /** One report for the run: rows the file got wrong plus rows the domain refused (FR-4.6). */
    private static TemplateImportResult merge(
            TemplateImportResult result, List<TemplateImportResult.Failure> parseFailures) {
        if (parseFailures.isEmpty()) {
            return result;
        }
        List<TemplateImportResult.Failure> failures = new ArrayList<>(parseFailures);
        failures.addAll(result.failures());
        return new TemplateImportResult(result.created(), result.imported(), result.skipped(), failures);
    }

    private static void report(TemplateImportResult result) {
        LOG.info(
                "Template import finished: created={} versions={} skipped={} failed={}",
                result.created(),
                result.imported(),
                result.skipped(),
                result.failures().size());
        result.failures()
                .forEach(failure -> LOG.warn(
                        "Template import rejected code={} locale={}: {}",
                        failure.code(),
                        failure.locale(),
                        failure.reason()));
    }
}
