package uz.hamkorbank.commhub.application.service.support;

import org.springframework.stereotype.Component;
import uz.hamkorbank.commhub.application.exception.ConfigurationConflictException;
import uz.hamkorbank.commhub.application.exception.NotFoundException;
import uz.hamkorbank.commhub.application.port.out.TemplateRepository;
import uz.hamkorbank.commhub.domain.model.Template;
import uz.hamkorbank.commhub.domain.model.TemplateVersion;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.ContentLocale;
import uz.hamkorbank.commhub.domain.model.vo.TemplateCode;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * The single place that answers "may this template be sent, and which version" (ADR-0038, FR-4.2).
 *
 * <p>A send initiated from the panel takes its content only from a published template, because that is
 * what keeps a transport layer from becoming an authoring one: the wording has already been through
 * review by a second person. Both the estimate and the send resolve through here, so the version that
 * was priced and the version that goes out can never differ.
 *
 * <p>Refusals are exceptions rather than pipeline verdicts: an operator choosing an unpublished template
 * is a mistake in an interactive session, not traffic the Bank's systems have to handle (IR-01).
 */
@Component
public class PublishedTemplates {

    private final TemplateRepository templates;

    public PublishedTemplates(TemplateRepository templates) {
        this.templates = Guard.notNull(templates, "templates");
    }

    /**
     * The version that would be sent for this code, channel and locale.
     *
     * @throws NotFoundException the template does not exist on that channel
     * @throws ConfigurationConflictException it exists but has nothing sendable in that locale — it is
     *     archived, or the locale has only drafts
     */
    public Resolved require(TemplateCode code, Channel channel, ContentLocale locale) {
        Guard.notNull(code, "code");
        Guard.notNull(channel, "channel");
        Guard.notNull(locale, "locale");
        Template template =
                templates.findByCode(code, channel).orElseThrow(() -> NotFoundException.of("template", code.value()));
        if (!template.isSendable()) {
            throw new ConfigurationConflictException(
                    "template %s is archived and cannot be sent".formatted(code.value()));
        }
        TemplateVersion version = template.publishedVersion(locale)
                .orElseThrow(() -> new ConfigurationConflictException(
                        "template %s has no published version in %s".formatted(code.value(), locale)));
        return new Resolved(template, version);
    }

    /** A template together with the version that would actually be rendered from it. */
    public record Resolved(Template template, TemplateVersion version) {}
}
