package uz.hamkorbank.commhub.application.service.pipeline;

import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Component;
import uz.hamkorbank.commhub.application.policy.DeduplicationPolicy;
import uz.hamkorbank.commhub.application.port.out.DedupRegistryPort;
import uz.hamkorbank.commhub.domain.model.vo.DedupKey;
import uz.hamkorbank.commhub.domain.model.vo.ExternalMessageId;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Idempotency of submissions (FR-1.5).
 *
 * <p>The key is the one supplied by the source system or, by default,
 * {@code (streamId, externalMessageId)}. {@link #claim} is the authoritative check — it is atomic in
 * the registry — while {@link #findOriginal} only serves the cheap early exit; both are needed
 * because Kafka delivers at least once and two consumers may race on the same key (AD-03).
 */
@Component
public class DeduplicationService {

    private final DedupRegistryPort registry;
    private final DeduplicationPolicy policy;

    public DeduplicationService(DedupRegistryPort registry, DeduplicationPolicy policy) {
        this.registry = Guard.notNull(registry, "registry");
        this.policy = Guard.notNull(policy, "policy");
    }

    /** Key of a submission: the explicit one, otherwise {@code <streamId>:<externalMessageId>}. */
    public DedupKey keyOf(DedupKey explicitKey, StreamId streamId, ExternalMessageId externalMessageId) {
        return explicitKey != null ? explicitKey : DedupKey.forExternalId(streamId, externalMessageId);
    }

    /** Message that already owns the key inside the window, if any (FR-1.5). */
    public Optional<MessageId> findOriginal(DedupKey key, Instant now) {
        Guard.notNull(key, "key");
        Guard.notNull(now, "now");
        return registry.findOriginal(key, policy.windowStart(now));
    }

    /**
     * Claims the key for the message.
     *
     * @return empty when the claim succeeded, otherwise the message that won the race (FR-1.5)
     */
    public Optional<MessageId> claim(DedupKey key, MessageId messageId, Instant now) {
        Guard.notNull(key, "key");
        Guard.notNull(messageId, "messageId");
        Guard.notNull(now, "now");
        return registry.register(key, messageId, now, policy.expiresAt(now));
    }
}
