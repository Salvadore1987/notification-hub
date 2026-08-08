package uz.hamkorbank.commhub.application.port.out;

import java.util.List;

/**
 * Transactional outbox: the only way the core emits an event (AD-03, §10.1 {@code outbox_event}).
 *
 * <p>Events are appended inside the same transaction as the business change; a relay publishes them
 * to Kafka afterwards (Phase 5), which yields at-least-once delivery without a distributed
 * transaction. Consumers stay idempotent by {@code eventId}.
 */
public interface OutboxPort {

    void append(OutboxEvent event);

    void appendAll(List<OutboxEvent> events);
}
