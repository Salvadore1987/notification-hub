package uz.hamkorbank.commhub.application.port.in.command;

import uz.hamkorbank.commhub.domain.model.Actor;
import uz.hamkorbank.commhub.domain.model.vo.ProviderId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Removal of a provider profile (FR-2.1).
 *
 * <p>Refused while a channel still lists the provider in its fallback order: deleting the row would
 * silently shorten a fallback chain, and a chain that lost its reserve looks exactly like one that
 * never had it. Take the provider out of the order first, then delete it.
 */
public record DeleteProviderCommand(Actor actor, ProviderId providerId, String reason) {

    public DeleteProviderCommand {
        Guard.notNull(actor, "DeleteProviderCommand.actor");
        Guard.notNull(providerId, "DeleteProviderCommand.providerId");
    }
}
