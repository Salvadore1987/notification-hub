package uz.hamkorbank.commhub.application.port.in.command;

import java.time.Instant;
import java.util.Optional;
import uz.hamkorbank.commhub.domain.model.Actor;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.SuppressionReason;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Adds one address to the suppression list (FR-5.1).
 *
 * <p>The address arrives in the clear because that is what the person filling in the form has — a phone
 * number, an email — and is hashed by the use case before it reaches the database (DB-04). It is validated
 * by the value object of the channel on the way, so a mistyped number is refused here rather than becoming a
 * hash that silently matches nothing.
 *
 * @param channel channel the ban applies to; required for an address, since an address only exists on one
 * @param validUntil end of a temporary ban; {@code null} makes the entry permanent
 */
public record SuppressAddressCommand(
        Actor actor, Channel channel, String address, SuppressionReason reason, Instant validUntil) {

    public SuppressAddressCommand {
        Guard.notNull(actor, "SuppressAddressCommand.actor");
        Guard.notNull(channel, "SuppressAddressCommand.channel");
        Guard.notBlank(address, "SuppressAddressCommand.address");
        Guard.notNull(reason, "SuppressAddressCommand.reason");
    }

    /** Permanent ban, the usual case: a complaint or an opt-out (FR-5.1). */
    public static SuppressAddressCommand of(Actor actor, Channel channel, String address, SuppressionReason reason) {
        return new SuppressAddressCommand(actor, channel, address, reason, null);
    }

    public Optional<Instant> validUntilOptional() {
        return Optional.ofNullable(validUntil);
    }
}
