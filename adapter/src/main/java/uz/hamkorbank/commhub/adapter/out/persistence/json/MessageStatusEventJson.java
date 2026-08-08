package uz.hamkorbank.commhub.adapter.out.persistence.json;

import java.time.Instant;
import java.util.UUID;
import uz.hamkorbank.commhub.application.dto.MessageKey;
import uz.hamkorbank.commhub.application.dto.MessageStatusEvent;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.MessageStatus;
import uz.hamkorbank.commhub.domain.model.type.RejectionReason;
import uz.hamkorbank.commhub.domain.model.vo.BatchId;
import uz.hamkorbank.commhub.domain.model.vo.CorrelationId;
import uz.hamkorbank.commhub.domain.model.vo.ExternalMessageId;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;

/**
 * {@link MessageStatusEvent} inside {@code outbox_event.payload}, in the wire shape of §6.4.
 *
 * <p>The stored payload is the message that will reach the source systems, field for field: the outbox
 * relay of Phase 5 publishes it as it is. Writing the value objects directly would store
 * {@code {"provider":{"value":"PLAYMOBILE"}}} and make the storage format an accident of the domain
 * classes instead of the published contract.
 *
 * <p>Nested records keep the header at eight components — the project limit — while §6.4 stays flat on
 * the wire; the relay flattens {@code key} and {@code outcome} when it serializes for Kafka.
 */
public record MessageStatusEventJson(UUID eventId, String occurredAt, KeyJson key, OutcomeJson outcome, int segments) {

    public static MessageStatusEventJson of(MessageStatusEvent event) {
        return new MessageStatusEventJson(
                event.eventId(),
                event.occurredAt().toString(),
                KeyJson.of(event.key()),
                OutcomeJson.of(event),
                event.segments());
    }

    public MessageStatusEvent toDomain() {
        return new MessageStatusEvent(
                eventId,
                Instant.parse(occurredAt),
                key.toDomain(),
                outcome.channel() == null ? null : Channel.valueOf(outcome.channel()),
                outcome.provider() == null ? null : ProviderCode.of(outcome.provider()),
                MessageStatus.valueOf(outcome.status()),
                outcome.providerStatus(),
                outcome.reasonToDomain(),
                segments);
    }

    /** Identity of the message the event is about (§6.4). */
    public record KeyJson(
            String streamId, String batchId, UUID messageId, String externalMessageId, String correlationId) {

        public static KeyJson of(MessageKey key) {
            return new KeyJson(
                    key.streamId().value(),
                    key.batchId() == null ? null : key.batchId().value().toString(),
                    key.messageId().value(),
                    key.externalMessageId().value(),
                    key.correlationId().value());
        }

        public MessageKey toDomain() {
            return new MessageKey(
                    StreamId.of(streamId),
                    batchId == null ? null : BatchId.fromString(batchId),
                    MessageId.of(messageId),
                    ExternalMessageId.of(externalMessageId),
                    CorrelationId.of(correlationId));
        }
    }

    /** What happened to the message: canonical status plus the provider's own words (§6.3). */
    public record OutcomeJson(
            String channel, String provider, String status, String providerStatus, String reason, String reasonDetail) {

        public static OutcomeJson of(MessageStatusEvent event) {
            return new OutcomeJson(
                    event.channel() == null ? null : event.channel().name(),
                    event.provider() == null ? null : event.provider().value(),
                    event.status().name(),
                    event.providerStatus(),
                    event.reasonOptional().map(reason -> reason.code().name()).orElse(null),
                    event.reasonOptional()
                            .map(MessageStatusEvent.StatusReason::detail)
                            .orElse(null));
        }

        public MessageStatusEvent.StatusReason reasonToDomain() {
            return reason == null
                    ? null
                    : new MessageStatusEvent.StatusReason(RejectionReason.valueOf(reason), reasonDetail);
        }
    }
}
