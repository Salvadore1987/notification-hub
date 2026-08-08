package uz.hamkorbank.commhub.application.port.out;

import java.time.Instant;
import java.util.UUID;
import uz.hamkorbank.commhub.application.dto.OutboxPayload;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * An outbox row the relay has picked up but not yet published (AD-03).
 *
 * <p>Distinct from {@link OutboxEvent}, which is what a use case appends: this one carries what only
 * the store knows — when the row was written and how many publication attempts it has behind it. The
 * pair {@code (eventId, createdAt)} is the primary key of the partitioned table, so both travel back
 * to the store when the row is marked.
 *
 * @param attempts failed publications so far; a value that keeps growing means the relay is stuck on
 *     this row and an operator has to look at {@code outbox_event.last_error}
 */
public record PendingOutboxEvent(
        UUID eventId,
        Instant createdAt,
        OutboxEventType type,
        String aggregateType,
        String aggregateId,
        OutboxPayload payload,
        int attempts) {

    public PendingOutboxEvent {
        Guard.notNull(eventId, "PendingOutboxEvent.eventId");
        Guard.notNull(createdAt, "PendingOutboxEvent.createdAt");
        Guard.notNull(type, "PendingOutboxEvent.type");
        Guard.notBlank(aggregateType, "PendingOutboxEvent.aggregateType");
        Guard.notBlank(aggregateId, "PendingOutboxEvent.aggregateId");
        Guard.notNull(payload, "PendingOutboxEvent.payload");
        Guard.notNegative(attempts, "PendingOutboxEvent.attempts");
    }
}
