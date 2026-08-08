package uz.hamkorbank.commhub.domain.model;

import java.util.Optional;
import uz.hamkorbank.commhub.domain.model.type.Priority;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;
import uz.hamkorbank.commhub.domain.model.vo.BatchId;
import uz.hamkorbank.commhub.domain.model.vo.CorrelationId;
import uz.hamkorbank.commhub.domain.model.vo.DedupKey;
import uz.hamkorbank.commhub.domain.model.vo.ExternalMessageId;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Channel-independent envelope of a message (MP-01, SRS §5.2).
 *
 * <p>Identity, source stream, traffic class, priority, idempotency key and tracing — everything the
 * pipeline needs before it knows which channel will be used.
 *
 * @param batchId {@code null} for single messages
 */
public record MessageEnvelope(
        MessageId id,
        ExternalMessageId externalId,
        StreamId streamId,
        BatchId batchId,
        TrafficClass trafficClass,
        Priority priority,
        DedupKey dedupKey,
        CorrelationId correlationId) {

    public MessageEnvelope {
        Guard.notNull(id, "MessageEnvelope.id");
        Guard.notNull(externalId, "MessageEnvelope.externalId");
        Guard.notNull(streamId, "MessageEnvelope.streamId");
        Guard.notNull(trafficClass, "MessageEnvelope.trafficClass");
        Guard.notNull(priority, "MessageEnvelope.priority");
        Guard.notNull(dedupKey, "MessageEnvelope.dedupKey");
        Guard.notNull(correlationId, "MessageEnvelope.correlationId");
    }

    /**
     * Envelope of a single message: identity and dedup key are derived, the priority defaults to the
     * one of the traffic class (FR-1.5, TC-02, PM-03).
     */
    public static MessageEnvelope single(StreamId streamId, ExternalMessageId externalId, TrafficClass trafficClass) {
        Guard.notNull(trafficClass, "MessageEnvelope.trafficClass");
        return new MessageEnvelope(
                MessageId.newId(),
                externalId,
                streamId,
                null,
                trafficClass,
                trafficClass.defaultPriority(),
                DedupKey.forExternalId(streamId, externalId),
                CorrelationId.newId());
    }

    /** Envelope of a batch item (FR-1.6). */
    public static MessageEnvelope batched(
            StreamId streamId, ExternalMessageId externalId, TrafficClass trafficClass, BatchId batchId) {
        Guard.notNull(trafficClass, "MessageEnvelope.trafficClass");
        return new MessageEnvelope(
                MessageId.newId(),
                externalId,
                streamId,
                batchId,
                trafficClass,
                trafficClass.defaultPriority(),
                DedupKey.forExternalId(streamId, externalId),
                CorrelationId.newId());
    }

    public boolean isBatched() {
        return batchId != null;
    }

    public Optional<BatchId> batchIdOptional() {
        return Optional.ofNullable(batchId);
    }

    /** Returns a copy with an explicit priority requested by the source system. */
    public MessageEnvelope withPriority(Priority requestedPriority) {
        return new MessageEnvelope(
                id, externalId, streamId, batchId, trafficClass, requestedPriority, dedupKey, correlationId);
    }

    /** Returns a copy with an explicit dedup key supplied by the source system (FR-1.5). */
    public MessageEnvelope withDedupKey(DedupKey explicitDedupKey) {
        return new MessageEnvelope(
                id, externalId, streamId, batchId, trafficClass, priority, explicitDedupKey, correlationId);
    }

    /** Returns a copy with the correlation id propagated by the caller (FR-8.6). */
    public MessageEnvelope withCorrelationId(CorrelationId propagatedCorrelationId) {
        return new MessageEnvelope(
                id, externalId, streamId, batchId, trafficClass, priority, dedupKey, propagatedCorrelationId);
    }
}
