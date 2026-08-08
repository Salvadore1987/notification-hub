package uz.hamkorbank.commhub.application.dto;

import java.time.Instant;
import java.util.UUID;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;

/**
 * What an outbox row carries (AD-03, §10.1 {@code outbox_event}).
 *
 * <p>Sealed and small on purpose. Everything the Hub publishes to the source systems goes through the
 * outbox, so this interface is the list of outbound contracts: the canonical status of §6.4 and the
 * invalidated push token of PU-04. Adding a third means adding a topic, a codec and a stored shape, and
 * the compiler asks for all three at once — which is the point of sealing it rather than accepting
 * {@code Object}.
 *
 * <p>The three accessors are what the plumbing needs and nothing more: the relay deduplicates by
 * {@link #eventId()}, the store orders by {@link #occurredAt()}, and the broker headers carry
 * {@link #streamId()} so a consumer can filter without parsing the body (§8.1 IK-02).
 */
public sealed interface OutboxPayload permits MessageStatusEvent, PushTokenInvalidatedEvent {

    UUID eventId();

    Instant occurredAt();

    /** Stream the event belongs to; travels in the {@code commhub-stream-id} header. */
    StreamId streamId();
}
