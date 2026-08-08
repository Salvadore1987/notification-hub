package uz.hamkorbank.commhub.application.port.out;

import uz.hamkorbank.commhub.application.dto.MessageStatusEvent;

/**
 * Publishes canonical status events to the source systems (§6.4, §8.1 IK-02).
 *
 * <p>Called by the outbox relay only — use cases never publish directly, they append to the
 * {@link OutboxPort} inside their transaction (AD-03).
 */
public interface StatusPublisherPort {

    /** Publishes to {@code comm.outbound.status.v1}; must be idempotent by {@code eventId}. */
    void publishStatus(MessageStatusEvent event);

    /** Publishes to {@code comm.outbound.dlq.v1} (FR-3.3). */
    void publishDlq(MessageStatusEvent event);
}
