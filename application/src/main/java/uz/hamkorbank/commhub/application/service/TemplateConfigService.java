package uz.hamkorbank.commhub.application.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.hamkorbank.commhub.application.dto.TemplateVersionView;
import uz.hamkorbank.commhub.application.dto.TemplateView;
import uz.hamkorbank.commhub.application.exception.ConfigurationConflictException;
import uz.hamkorbank.commhub.application.exception.NotFoundException;
import uz.hamkorbank.commhub.application.mapper.TemplateMapper;
import uz.hamkorbank.commhub.application.port.in.ManageTemplates;
import uz.hamkorbank.commhub.application.port.in.command.CreateTemplateCommand;
import uz.hamkorbank.commhub.application.port.in.command.MapProviderTemplateCommand;
import uz.hamkorbank.commhub.application.port.in.command.SaveTemplateVersionCommand;
import uz.hamkorbank.commhub.application.port.in.command.TemplateStateCommand;
import uz.hamkorbank.commhub.application.port.in.command.TemplateVersionStateCommand;
import uz.hamkorbank.commhub.application.port.in.command.UnmapProviderTemplateCommand;
import uz.hamkorbank.commhub.application.port.in.command.UpdateTemplateCommand;
import uz.hamkorbank.commhub.application.port.out.ClockPort;
import uz.hamkorbank.commhub.application.port.out.ProviderConfigRepository;
import uz.hamkorbank.commhub.application.port.out.TemplateRepository;
import uz.hamkorbank.commhub.application.service.pipeline.PanDetector;
import uz.hamkorbank.commhub.application.service.support.ConfigAuditor;
import uz.hamkorbank.commhub.domain.model.Actor;
import uz.hamkorbank.commhub.domain.model.Provider;
import uz.hamkorbank.commhub.domain.model.Template;
import uz.hamkorbank.commhub.domain.model.TemplateVersion;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.TemplateCatalogStatus;
import uz.hamkorbank.commhub.domain.model.vo.TemplateCode;
import uz.hamkorbank.commhub.domain.model.vo.TemplateId;
import uz.hamkorbank.commhub.domain.model.vo.TemplateVersionId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Template administration: cards, versions, review workflow, provider-side mapping (FR-4.1, FR-4.2,
 * FR-4.5).
 *
 * <p>The workflow rules themselves are not here — the transition table lives in {@code TemplateStatus},
 * maker/checker in {@code TemplateVersion.publish} and "one published version per locale" in
 * {@code Template.publishVersion}. What this service owns is the part the domain cannot see: who the actor
 * is, when "now" is, whether the code is free, and that every change leaves an audit entry (FR-7.3).
 *
 * <p>The author of a version and the reviewer of a publication are both taken from the actor, never from a
 * field of the command. A maker/checker rule that trusts a name typed into a form is not a control
 * (FR-4.2).
 */
@Service
public class TemplateConfigService implements ManageTemplates {

    private static final String ENTITY = "template";

    /** How much of a body an audit entry carries; see {@link #describe(TemplateVersion)}. */
    private static final int MAX_AUDITED_TEXT = 200;

    private final TemplateRepository templates;
    private final ProviderConfigRepository providers;
    private final ClockPort clock;
    private final TemplateMapper mapper;
    private final ConfigAuditor auditor;
    private final PanDetector panDetector;

    public TemplateConfigService(
            TemplateRepository templates,
            ProviderConfigRepository providers,
            ClockPort clock,
            TemplateMapper mapper,
            ConfigAuditor auditor,
            PanDetector panDetector) {
        this.templates = Guard.notNull(templates, "templates");
        this.providers = Guard.notNull(providers, "providers");
        this.clock = Guard.notNull(clock, "clock");
        this.mapper = Guard.notNull(mapper, "mapper");
        this.auditor = Guard.notNull(auditor, "auditor");
        this.panDetector = Guard.notNull(panDetector, "panDetector");
    }

    @Override
    @Transactional
    public TemplateView create(CreateTemplateCommand command) {
        Guard.notNull(command, "command");
        templates.findByCode(command.code()).ifPresent(existing -> {
            throw new ConfigurationConflictException("template %s already exists on channel %s"
                    .formatted(existing.code().value(), existing.channel()));
        });
        Template template = Template.create(
                TemplateId.newId(), command.code(), command.channel(), command.direction(), command.owner());
        templates.save(template);
        auditor.record(
                command.actor(), "template.create", ENTITY, template.code().value(), null, describe(template));
        return mapper.toView(template);
    }

    @Override
    @Transactional
    public TemplateView update(UpdateTemplateCommand command) {
        Guard.notNull(command, "command");
        Template template = require(command.code());
        String before = describe(template);
        template.updateDirection(command.direction());
        template.updateOwner(command.owner());
        templates.save(template);
        auditor.record(
                command.actor(), "template.update", ENTITY, template.code().value(), before, describe(template));
        return mapper.toView(template);
    }

    @Override
    @Transactional
    public TemplateView changeState(TemplateStateCommand command) {
        Guard.notNull(command, "command");
        Template template = require(command.code());
        String before = describe(template);
        if (command.status() == TemplateCatalogStatus.ARCHIVED) {
            template.archive();
        } else {
            template.restore();
        }
        templates.save(template);
        auditor.record(
                command.actor(),
                "template.state",
                ENTITY,
                template.code().value(),
                before,
                describe(template)
                        + command.reasonOptional()
                                .map(reason -> ", reason=" + reason)
                                .orElse(""));
        return mapper.toView(template);
    }

    @Override
    @Transactional
    public TemplateVersionView saveVersion(SaveTemplateVersionCommand command) {
        Guard.notNull(command, "command");
        Template template = require(command.code());
        if (!template.isSendable()) {
            throw new ConfigurationConflictException("template %s is archived and cannot be edited"
                    .formatted(command.code().value()));
        }
        TemplateVersion.Body body = new TemplateVersion.Body(command.subject(), command.text(), command.htmlBody());
        rejectHtmlOutsideEmail(template, body);
        rejectCardNumbers(body);
        String before = command.versionOptional()
                .flatMap(number -> template.version(command.locale(), number))
                .map(TemplateConfigService::describe)
                .orElse(null);
        TemplateVersion version = command.versionOptional()
                .map(number -> rewrite(template, command, number, body))
                .orElseGet(() -> append(template, command, body));
        templates.save(template);
        auditor.record(
                command.actor(),
                "template.version.save",
                ENTITY,
                versionKey(template.code(), version),
                before,
                describe(version));
        return mapper.toView(version);
    }

    @Override
    @Transactional
    public TemplateVersionView changeVersionState(TemplateVersionStateCommand command) {
        Guard.notNull(command, "command");
        Template template = require(command.code());
        TemplateVersion version = template.version(command.locale(), command.version())
                .orElseThrow(() -> NotFoundException.of(
                        "template version", versionKey(command.code(), command.locale() + "/" + command.version())));
        String before = describe(version);
        switch (command.status()) {
            case ON_REVIEW -> version.submitForReview();
            case DRAFT -> version.returnToDraft();
            case PUBLISHED -> template.publishVersion(version, identity(command.actor()), clock.now());
            case ARCHIVED -> version.archive();
            default -> throw new IllegalStateException("unknown template status " + command.status());
        }
        templates.save(template);
        auditor.record(
                command.actor(),
                "template.version.state",
                ENTITY,
                versionKey(template.code(), version),
                before,
                describe(version));
        return mapper.toView(version);
    }

    @Override
    @Transactional
    public TemplateView mapProviderTemplate(MapProviderTemplateCommand command) {
        Guard.notNull(command, "command");
        Template template = require(command.code());
        Provider provider = providers
                .findProviderByCode(command.providerCode())
                .orElseThrow(() -> new ConfigurationConflictException("provider %s is not registered"
                        .formatted(command.providerCode().value())));
        if (provider.channel() != template.channel()) {
            throw new ConfigurationConflictException("provider %s serves channel %s, template %s is a %s template"
                    .formatted(
                            provider.code().value(),
                            provider.channel(),
                            template.code().value(),
                            template.channel()));
        }
        String before = describeMapping(template, command);
        template.mapToProviderTemplate(
                new Template.ProviderMapping(command.providerCode(), command.providerTemplateId(), command.approved()));
        templates.save(template);
        auditor.record(
                command.actor(),
                "template.provider-mapping",
                ENTITY,
                template.code().value(),
                before,
                describeMapping(template, command));
        return mapper.toView(template);
    }

    @Override
    @Transactional
    public TemplateView unmapProviderTemplate(UnmapProviderTemplateCommand command) {
        Guard.notNull(command, "command");
        Template template = require(command.code());
        String before = describeMapping(template, command.providerCode().value());
        if (!template.unmapProviderTemplate(command.providerCode())) {
            throw NotFoundException.of(
                    "provider mapping",
                    "%s/%s"
                            .formatted(
                                    command.code().value(),
                                    command.providerCode().value()));
        }
        templates.save(template);
        auditor.record(
                command.actor(),
                "template.provider-mapping.delete",
                ENTITY,
                template.code().value(),
                before,
                null);
        return mapper.toView(template);
    }

    /** Opens the next version of the locale as a draft authored by the actor (FR-4.1). */
    private TemplateVersion append(Template template, SaveTemplateVersionCommand command, TemplateVersion.Body body) {
        TemplateVersion version = TemplateVersion.draft(
                TemplateVersionId.newId(),
                template.id(),
                template.nextVersionNumber(command.locale()),
                command.locale(),
                body,
                identity(command.actor()));
        template.addVersion(version);
        return version;
    }

    /** Rewrites an existing draft; the domain refuses anything past DRAFT (FR-4.1). */
    private static TemplateVersion rewrite(
            Template template, SaveTemplateVersionCommand command, int number, TemplateVersion.Body body) {
        TemplateVersion version = template.version(command.locale(), number)
                .orElseThrow(() -> NotFoundException.of(
                        "template version", versionKey(command.code(), command.locale() + "/" + number)));
        version.updateBody(body);
        return version;
    }

    /**
     * A template body may not contain a card number (PCI DSS, SEC-05).
     *
     * <p>Unconditional, unlike the sending path, where the mode is configurable ({@code PanPolicy}): the
     * alert-only mode exists for traffic already in flight from source systems that have to be found and
     * fixed, whereas a template is written once by a person who is sitting in front of the form. A card
     * number belongs in a merge field, masked by whoever fills it — never in the wording.
     */
    private void rejectCardNumbers(TemplateVersion.Body body) {
        if (panDetector.containsPan(body.text())
                || panDetector.containsPan(body.subject())
                || panDetector.containsPan(body.html())) {
            throw new ConfigurationConflictException(
                    "the template body contains a card number; a PAN must not be stored in a template (SEC-05)");
        }
    }

    /**
     * Only an email template may carry an HTML alternative (EM-01).
     *
     * <p>An SMS or a push has nowhere to put it: the adapter would send the plain text and the HTML would
     * live on as a body nobody reads and everybody edits. Refusing it here is how the operator learns that
     * the template they are writing is on the wrong channel.
     */
    private static void rejectHtmlOutsideEmail(Template template, TemplateVersion.Body body) {
        if (body.hasHtml() && template.channel() != Channel.EMAIL) {
            throw new ConfigurationConflictException(
                    "an HTML body is only carried by an email template; %s is on channel %s (EM-01)"
                            .formatted(template.code().value(), template.channel()));
        }
    }

    private Template require(TemplateCode code) {
        return templates.findByCode(code).orElseThrow(() -> NotFoundException.of(ENTITY, code.value()));
    }

    /**
     * Name recorded as the author of a draft or the reviewer of a publication (FR-4.2).
     *
     * <p>Maker/checker separates two people, so a nameless actor — the Hub itself — has nobody to be
     * separated from. Templates are written and approved by users; the scheduler has no business here.
     */
    private static String identity(Actor actor) {
        if (actor.id() == null || actor.id().isBlank()) {
            throw new ConfigurationConflictException(
                    "editing a template requires a named actor, %s has none (FR-4.2)".formatted(actor.type()));
        }
        return actor.id();
    }

    private static String describe(Template template) {
        return "status=%s, channel=%s, direction=%s, owner=%s"
                .formatted(
                        template.catalogStatus(),
                        template.channel(),
                        template.direction().orElse(null),
                        template.owner().orElse(null));
    }

    /**
     * Rendered state of a version for the audit trail (FR-7.3).
     *
     * <p>The beginning of the body is included: rewriting a draft changes nothing else, and an audit entry
     * whose before and after are identical does not record anything. Truncated because the entry is
     * evidence of a change, not a second copy of the template. Merge fields are field <em>names</em> —
     * no customer data passes through here (SEC-08).
     */
    private static String describe(TemplateVersion version) {
        String text = version.body().text();
        String excerpt = text.length() <= MAX_AUDITED_TEXT ? text : text.substring(0, MAX_AUDITED_TEXT) + "…";
        return "locale=%s, version=%d, status=%s, variables=%s, text=%s"
                .formatted(version.locale(), version.version(), version.status(), version.declaredVariables(), excerpt);
    }

    private static String describeMapping(Template template, MapProviderTemplateCommand command) {
        return describeMapping(template, command.providerCode().value());
    }

    private static String describeMapping(Template template, String providerCode) {
        return template.providerMappings().values().stream()
                .filter(mapping -> mapping.providerCode().value().equals(providerCode))
                .map(mapping ->
                        "%s=%s, approved=%s".formatted(providerCode, mapping.providerTemplateId(), mapping.approved()))
                .findFirst()
                .orElse(providerCode + "=none");
    }

    private static String versionKey(TemplateCode code, TemplateVersion version) {
        return versionKey(code, version.locale() + "/" + version.version());
    }

    private static String versionKey(TemplateCode code, String suffix) {
        return code.value() + "/" + suffix;
    }
}
