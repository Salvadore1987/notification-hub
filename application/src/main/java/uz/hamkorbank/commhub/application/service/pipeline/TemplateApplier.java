package uz.hamkorbank.commhub.application.service.pipeline;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import uz.hamkorbank.commhub.application.port.out.TemplateRepository;
import uz.hamkorbank.commhub.domain.exception.DomainValidationException;
import uz.hamkorbank.commhub.domain.model.Template;
import uz.hamkorbank.commhub.domain.model.TemplateRef;
import uz.hamkorbank.commhub.domain.model.TemplateVersion;
import uz.hamkorbank.commhub.domain.model.content.EmailContent;
import uz.hamkorbank.commhub.domain.model.content.MessageContent;
import uz.hamkorbank.commhub.domain.model.content.MessageContents;
import uz.hamkorbank.commhub.domain.model.content.PushContent;
import uz.hamkorbank.commhub.domain.model.content.SmsContent;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.ContentLocale;
import uz.hamkorbank.commhub.domain.model.type.RejectionReason;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Renders the content of a message from its template (FR-4.1, FR-4.3).
 *
 * <p>Only a {@code PUBLISHED} version may be sent (FR-4.1). Substitution runs in strict mode: a merge
 * field without a value rejects the message with {@code TEMPLATE_VARIABLE_MISSING} instead of sending
 * a text with a hole in it (FR-4.3).
 *
 * <p>A submission may carry a template without any content at all — FR-1.2 explicitly allows
 * "content by template" — in which case the rendered body becomes the content of the template's
 * channel.
 */
@Component
public class TemplateApplier {

    /** Locale used when neither the submission nor the stream asks for one (FR-4.1). */
    public static final ContentLocale DEFAULT_LOCALE = ContentLocale.RU;

    private final TemplateRepository templates;

    public TemplateApplier(TemplateRepository templates) {
        this.templates = Guard.notNull(templates, "templates");
    }

    /**
     * Applies the template of a submission, if it has one.
     *
     * @param contents content submitted by the source system; may be {@code null} with a template
     * @param ref template reference with its merge variables; {@code null} leaves the content as is
     */
    public TemplateOutcome apply(MessageContents contents, TemplateRef ref, ContentLocale streamLocale) {
        if (ref == null) {
            return TemplateOutcome.unchanged(Guard.notNull(contents, "contents"));
        }
        Optional<Template> template = templates.findByCode(ref.code());
        if (template.isEmpty()) {
            return TemplateOutcome.rejected(
                    RejectionReason.VALIDATION_FAILED,
                    "unknown template " + ref.code().value());
        }
        if (!template.get().isSendable()) {
            return TemplateOutcome.rejected(
                    RejectionReason.TEMPLATE_NOT_PUBLISHED,
                    "template %s is archived".formatted(ref.code().value()));
        }
        ContentLocale locale = ref.localeOptional().orElse(streamLocale == null ? DEFAULT_LOCALE : streamLocale);
        Optional<TemplateVersion> version = publishedVersion(template.get(), locale);
        if (version.isEmpty()) {
            return TemplateOutcome.rejected(
                    RejectionReason.TEMPLATE_NOT_PUBLISHED,
                    "template %s has no published version for locale %s"
                            .formatted(ref.code().value(), locale));
        }
        try {
            TemplateVersion.Rendered rendered = version.get().render(ref.variables(), true);
            MessageContent content = merge(template.get().channel(), contents, rendered);
            MessageContents result = contents == null ? MessageContents.of(content) : contents.with(content);
            return TemplateOutcome.rendered(result, template.get(), version.get());
        } catch (DomainValidationException e) {
            return TemplateOutcome.rejected(RejectionReason.TEMPLATE_VARIABLE_MISSING, e.getMessage());
        }
    }

    /** Requested locale first, then the default one — a template need not exist in every locale. */
    private static Optional<TemplateVersion> publishedVersion(Template template, ContentLocale locale) {
        Optional<TemplateVersion> requested = template.publishedVersion(locale);
        return requested.isPresent() || locale == DEFAULT_LOCALE
                ? requested
                : template.publishedVersion(DEFAULT_LOCALE);
    }

    /** Puts the rendered text into the content of the template's channel, keeping the rest of it. */
    private static MessageContent merge(Channel channel, MessageContents contents, TemplateVersion.Rendered rendered) {
        Optional<MessageContent> existing = contents == null ? Optional.empty() : contents.forChannel(channel);
        return switch (channel) {
            case SMS ->
                existing.map(SmsContent.class::cast)
                        .map(sms -> sms.withText(rendered.text()))
                        .orElseGet(() -> SmsContent.of(rendered.text()));
            case EMAIL -> mergeEmail(existing.map(EmailContent.class::cast).orElse(null), rendered);
            case PUSH -> mergePush(existing.map(PushContent.class::cast).orElse(null), rendered);
        };
    }

    /**
     * Puts the rendered subject and both body alternatives into the email content (FR-4.3, EM-01).
     *
     * <p>The HTML of the template wins over the HTML of the submission, exactly as its text does: the
     * template is what the message is being rendered from. What the submission keeps is everything the
     * template has no opinion about — its attachments and its sender. A template without an HTML body
     * leaves the submitted one in place rather than dropping it: a source system that sent HTML and named a
     * plain-text template would otherwise silently lose the half of the message its customers actually see.
     */
    private static EmailContent mergeEmail(EmailContent existing, TemplateVersion.Rendered rendered) {
        String subject =
                rendered.subjectOptional().orElseGet(() -> existing == null ? rendered.text() : existing.subject());
        if (existing == null) {
            return new EmailContent(subject, rendered.html(), rendered.text(), List.of(), null);
        }
        return new EmailContent(
                subject,
                rendered.htmlOptional().orElseGet(existing::htmlBody),
                rendered.text(),
                existing.attachments(),
                existing.from());
    }

    private static PushContent mergePush(PushContent existing, TemplateVersion.Rendered rendered) {
        String title =
                rendered.subjectOptional().orElseGet(() -> existing == null ? rendered.text() : existing.title());
        if (existing == null) {
            return new PushContent(title, rendered.text(), Map.of(), null, null);
        }
        return new PushContent(title, rendered.text(), existing.data(), existing.deepLink(), existing.image());
    }

    /** Locales a template is currently publishable in; used by the admin preview (FR-4.4). */
    public List<ContentLocale> publishedLocales(Template template) {
        Guard.notNull(template, "template");
        return List.copyOf(template.publishedVersions().keySet());
    }
}
