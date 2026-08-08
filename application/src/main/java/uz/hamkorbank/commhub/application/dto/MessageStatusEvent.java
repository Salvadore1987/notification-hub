package uz.hamkorbank.commhub.application.dto;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.MessageStatus;
import uz.hamkorbank.commhub.domain.model.type.RejectionReason;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Canonical status event delivered to the source systems (§6.4, MP-04).
 *
 * <p>Identical for every channel and provider: adapters map provider statuses onto
 * {@link MessageStatus} (ST-03) and the raw value travels along in {@link #providerStatus()}. The
 * event is written to the outbox inside the business transaction and published by the relay to
 * {@code comm.outbound.status.v1} (AD-03, §8.1).
 *
 * @param channel channel the message was routed to; {@code null} when it was rejected before routing
 * @param provider provider that reported the status; {@code null} for internal transitions
 * @param segments SMS segments of the message; 0 for other channels and before segmentation (MP-06)
 */
public record MessageStatusEvent(
        UUID eventId,
        Instant occurredAt,
        MessageKey key,
        Channel channel,
        ProviderCode provider,
        MessageStatus status,
        String providerStatus,
        StatusReason reason,
        int segments) {

    public MessageStatusEvent {
        Guard.notNull(eventId, "MessageStatusEvent.eventId");
        Guard.notNull(occurredAt, "MessageStatusEvent.occurredAt");
        Guard.notNull(key, "MessageStatusEvent.key");
        Guard.notNull(status, "MessageStatusEvent.status");
        Guard.notNegative(segments, "MessageStatusEvent.segments");
    }

    public Optional<Channel> channelOptional() {
        return Optional.ofNullable(channel);
    }

    public Optional<ProviderCode> providerOptional() {
        return Optional.ofNullable(provider);
    }

    public Optional<StatusReason> reasonOptional() {
        return Optional.ofNullable(reason);
    }

    /**
     * Reason of a non-delivery status (§6.4 {@code reason}, IR-01).
     *
     * @param detail human-readable explanation, e.g. the provider error description (PR-03)
     */
    public record StatusReason(RejectionReason code, String detail) {

        public StatusReason {
            Guard.notNull(code, "StatusReason.code");
        }

        public static StatusReason of(RejectionReason code, String detail) {
            return new StatusReason(code, detail);
        }
    }
}
