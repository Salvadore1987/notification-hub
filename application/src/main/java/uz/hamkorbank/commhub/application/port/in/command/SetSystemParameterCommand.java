package uz.hamkorbank.commhub.application.port.in.command;

import uz.hamkorbank.commhub.domain.model.Actor;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Writing or removing one system parameter (§11.2 "Администрирование").
 *
 * <p>The actor is mandatory and is never taken from a field of the request: a parameter change is one of
 * the critical operations of SEC-03, and the journal has to name a person rather than repeat what the
 * request claimed about itself.
 *
 * @param value {@code null} for a removal
 */
public record SetSystemParameterCommand(String key, String value, String description, Actor actor) {

    public SetSystemParameterCommand {
        Guard.notBlank(key, "SetSystemParameterCommand.key");
        Guard.notNull(actor, "SetSystemParameterCommand.actor");
    }

    public static SetSystemParameterCommand remove(String key, Actor actor) {
        return new SetSystemParameterCommand(key, null, null, actor);
    }
}
