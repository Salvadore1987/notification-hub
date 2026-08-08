package uz.hamkorbank.commhub.application.port.out;

import java.time.Instant;
import java.util.Optional;
import uz.hamkorbank.commhub.domain.model.vo.DedupKey;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;

/**
 * Idempotency registry of accepted submissions (§10.1 {@code dedup_registry}, FR-1.5).
 *
 * <p>{@link #register(DedupKey, MessageId, Instant, Instant)} is the authoritative check: it must be
 * atomic (unique key on {@code dedup_key}), so that two concurrent submissions of the same key — the
 * normal consequence of at-least-once Kafka delivery — cannot both be accepted (AD-03).
 */
public interface DedupRegistryPort {

    /**
     * Claims the key for the message.
     *
     * @return empty when the key was free and is now claimed, otherwise the message that owns it
     */
    Optional<MessageId> register(DedupKey key, MessageId messageId, Instant registeredAt, Instant expiresAt);

    /** Message that owns the key inside the dedup window, without claiming it (FR-1.5). */
    Optional<MessageId> findOriginal(DedupKey key, Instant windowStart);

    /** Drops entries whose window has elapsed; called by the retention job (DB-03). */
    long purgeExpired(Instant now, int limit);
}
