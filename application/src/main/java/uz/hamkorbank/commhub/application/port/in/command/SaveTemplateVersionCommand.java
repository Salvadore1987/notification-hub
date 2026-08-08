package uz.hamkorbank.commhub.application.port.in.command;

import java.util.Optional;
import uz.hamkorbank.commhub.domain.model.Actor;
import uz.hamkorbank.commhub.domain.model.type.ContentLocale;
import uz.hamkorbank.commhub.domain.model.vo.TemplateCode;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Writes the body of a template version in one locale (FR-4.1, FR-4.3).
 *
 * <p>{@code version} decides between the two things an editor does: {@code null} opens the next version
 * of the locale as a fresh draft, a number rewrites that draft. Rewriting is only possible while it is a
 * draft — a version under review or already published is what a reviewer approved and what messages were
 * rendered from, so a new text becomes a new version instead.
 *
 * <p>The author is {@link #actor()}: maker/checker only works if the author is who the Hub saw editing,
 * not a name typed into a form (FR-4.2).
 *
 * @param version version of the locale to rewrite; {@code null} creates the next one
 * @param subject email subject; {@code null} for SMS and for push titles carried in the text
 * @param text body with {@code {MERGE_FIELDS}} (FR-4.3); for an email, its plain-text alternative
 * @param htmlBody HTML alternative of an email body; {@code null} everywhere else (EM-01)
 */
public record SaveTemplateVersionCommand(
        Actor actor,
        TemplateCode code,
        ContentLocale locale,
        Integer version,
        String subject,
        String text,
        String htmlBody) {

    public SaveTemplateVersionCommand {
        Guard.notNull(actor, "SaveTemplateVersionCommand.actor");
        Guard.notNull(code, "SaveTemplateVersionCommand.code");
        Guard.notNull(locale, "SaveTemplateVersionCommand.locale");
        Guard.notBlank(text, "SaveTemplateVersionCommand.text");
        if (version != null) {
            Guard.positive(version, "SaveTemplateVersionCommand.version");
        }
    }

    public Optional<Integer> versionOptional() {
        return Optional.ofNullable(version);
    }
}
