package uz.hamkorbank.commhub.application.port.out;

import java.time.Instant;
import java.util.List;

/**
 * Transactional outbox: the only way the core emits an event (AD-03, §10.1 {@code outbox_event}).
 *
 * <p>Events are appended inside the same transaction as the business change; a relay publishes them
 * to Kafka afterwards, which yields at-least-once delivery without a distributed transaction.
 * Consumers stay idempotent by {@code eventId}.
 *
 * <p>The write side ({@link #append}) is used by the use cases, the read side ({@link #pollUnpublished}
 * and the two {@code mark…} methods) only by the relay. Both halves demand an active transaction: a
 * poll that does not hold its rows until they are marked would hand the same event to two instances.
 */
public interface OutboxPort {

    void append(OutboxEvent event);

    void appendAll(List<OutboxEvent> events);

    /**
     * Claims the oldest unpublished events for the caller's transaction, oldest first.
     *
     * <p>Rows already claimed by another instance are skipped rather than waited for, so relays on
     * several instances share the queue instead of serializing on it.
     */
    List<PendingOutboxEvent> pollUnpublished(int limit);

    /** Marks a claimed event as published; the relay calls it only after the broker acknowledged. */
    void markPublished(PendingOutboxEvent event, Instant publishedAt);

    /** Records a failed publication attempt; the event stays unpublished and is retried later. */
    void markFailed(PendingOutboxEvent event, String error);
}
