package uz.hamkorbank.commhub.application.port.out;

import java.time.Instant;
import java.util.UUID;
import uz.hamkorbank.commhub.application.dto.MessageStatusEvent;
import uz.hamkorbank.commhub.domain.support.Guard;
import uz.hamkorbank.commhub.domain.support.UuidV7;

/**
 * One row of the outbox (§10.1 {@code outbox_event}, AD-03).
 *
 * <p>The {@link #type()} decides the destination topic of the relay: {@code comm.outbound.status.v1}
 * for status events and {@code comm.outbound.dlq.v1} for DLQ events (§8.1 IK-02).
 *
 * @param aggregateId identifier of the aggregate the event belongs to; the partition key of the relay
 */
public record OutboxEvent(
        UUID eventId, OutboxEventType type, String aggregateType, String aggregateId, MessageStatusEvent payload) {

    public static final String AGGREGATE_MESSAGE = "message";

    public OutboxEvent {
        Guard.notNull(eventId, "OutboxEvent.eventId");
        Guard.notNull(type, "OutboxEvent.type");
        Guard.notBlank(aggregateType, "OutboxEvent.aggregateType");
        Guard.notBlank(aggregateId, "OutboxEvent.aggregateId");
        Guard.notNull(payload, "OutboxEvent.payload");
    }

    /** Status update for the source systems (§6.4). */
    public static OutboxEvent messageStatus(MessageStatusEvent payload) {
        return of(OutboxEventType.MESSAGE_STATUS, payload);
    }

    /** The message ended up in the DLQ (FR-3.3, IK-02). */
    public static OutboxEvent messageDlq(MessageStatusEvent payload) {
        return of(OutboxEventType.MESSAGE_DLQ, payload);
    }

    private static OutboxEvent of(OutboxEventType type, MessageStatusEvent payload) {
        Guard.notNull(payload, "payload");
        return new OutboxEvent(
                UuidV7.generate(),
                type,
                AGGREGATE_MESSAGE,
                payload.key().messageId().value().toString(),
                payload);
    }

    public Instant occurredAt() {
        return payload.occurredAt();
    }
}
