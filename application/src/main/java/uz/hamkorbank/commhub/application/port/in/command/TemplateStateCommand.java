package uz.hamkorbank.commhub.application.port.in.command;

import java.util.Optional;
import uz.hamkorbank.commhub.domain.model.Actor;
import uz.hamkorbank.commhub.domain.model.type.TemplateCatalogStatus;
import uz.hamkorbank.commhub.domain.model.vo.TemplateCode;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Archives a template card or brings it back (FR-4.1).
 *
 * <p>This is the "delete" of the template CRUD, and it is deliberately not one: the code of a template
 * appears in the history of every message rendered from it, so the row stays and leaves the working
 * catalogue instead (FR-7.3).
 *
 * @param reason why the card was archived; ends up in the audit entry (FR-7.3)
 */
public record TemplateStateCommand(Actor actor, TemplateCode code, TemplateCatalogStatus status, String reason) {

    public TemplateStateCommand {
        Guard.notNull(actor, "TemplateStateCommand.actor");
        Guard.notNull(code, "TemplateStateCommand.code");
        Guard.notNull(status, "TemplateStateCommand.status");
    }

    public Optional<String> reasonOptional() {
        return Optional.ofNullable(reason);
    }
}
