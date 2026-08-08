package uz.hamkorbank.commhub.application.port.in.command;

import java.time.Instant;
import java.util.Optional;
import uz.hamkorbank.commhub.domain.model.Actor;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.SuppressionReason;
import uz.hamkorbank.commhub.domain.model.vo.ClientId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Adds a whole client to the suppression list (FR-5.1).
 *
 * <p>A client-wide entry is what a "do not contact me at all" request produces: the client keeps changing
 * phone numbers and email addresses, and a ban tied to the addresses of today would quietly lapse.
 *
 * @param channel channel the ban applies to; {@code null} covers every channel
 * @param validUntil end of a temporary ban; {@code null} makes the entry permanent
 */
public record SuppressClientCommand(
        Actor actor, Channel channel, ClientId clientId, SuppressionReason reason, Instant validUntil) {

    public SuppressClientCommand {
        Guard.notNull(actor, "SuppressClientCommand.actor");
        Guard.notNull(clientId, "SuppressClientCommand.clientId");
        Guard.notNull(reason, "SuppressClientCommand.reason");
    }

    /** Permanent ban across every channel (FR-5.1). */
    public static SuppressClientCommand of(Actor actor, ClientId clientId, SuppressionReason reason) {
        return new SuppressClientCommand(actor, null, clientId, reason, null);
    }

    public Optional<Channel> channelOptional() {
        return Optional.ofNullable(channel);
    }

    public Optional<Instant> validUntilOptional() {
        return Optional.ofNullable(validUntil);
    }
}
