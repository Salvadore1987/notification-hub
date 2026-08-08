package uz.hamkorbank.commhub.application.port.in.command;

import uz.hamkorbank.commhub.domain.model.Actor;
import uz.hamkorbank.commhub.domain.model.vo.TemplateCode;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Editable attributes of a template card (FR-4.1).
 *
 * <p>Neither the code nor the channel is here: the code is what every source system refers the template
 * by and the channel decides what its body means. Both are the identity of the template, and changing an
 * identity in place is how integrations break silently.
 */
public record UpdateTemplateCommand(Actor actor, TemplateCode code, String direction, String owner) {

    public UpdateTemplateCommand {
        Guard.notNull(actor, "UpdateTemplateCommand.actor");
        Guard.notNull(code, "UpdateTemplateCommand.code");
    }
}
