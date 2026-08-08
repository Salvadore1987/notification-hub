package uz.hamkorbank.commhub.application.port.in.command;

import uz.hamkorbank.commhub.domain.model.Actor;
import uz.hamkorbank.commhub.domain.model.type.ContentLocale;
import uz.hamkorbank.commhub.domain.model.type.TemplateStatus;
import uz.hamkorbank.commhub.domain.model.vo.TemplateCode;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Moves one version through DRAFT → ON_REVIEW → PUBLISHED → ARCHIVED (FR-4.1, FR-4.2).
 *
 * <p>One command for all four transitions, because they are one workflow and the transition table lives
 * in {@code TemplateStatus} — an interface with a method per arrow would restate that table in a second
 * place. {@link #actor()} is the reviewer when the target is {@code PUBLISHED}, and the domain refuses
 * the publication if it is the author of the version (FR-4.2).
 */
public record TemplateVersionStateCommand(
        Actor actor, TemplateCode code, ContentLocale locale, int version, TemplateStatus status) {

    public TemplateVersionStateCommand {
        Guard.notNull(actor, "TemplateVersionStateCommand.actor");
        Guard.notNull(code, "TemplateVersionStateCommand.code");
        Guard.notNull(locale, "TemplateVersionStateCommand.locale");
        Guard.positive(version, "TemplateVersionStateCommand.version");
        Guard.notNull(status, "TemplateVersionStateCommand.status");
    }
}
