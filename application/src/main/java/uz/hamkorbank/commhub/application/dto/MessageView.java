package uz.hamkorbank.commhub.application.dto;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.MessageStatus;
import uz.hamkorbank.commhub.domain.model.type.RejectionReason;
import uz.hamkorbank.commhub.domain.model.vo.BatchId;
import uz.hamkorbank.commhub.domain.model.vo.CorrelationId;
import uz.hamkorbank.commhub.domain.model.vo.ExternalMessageId;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;
import uz.hamkorbank.commhub.domain.model.vo.Money;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * State of one message as its source system sees it (§8.2 {@code GET /messages}).
 *
 * <p>Deliberately free of content and of the recipient's address: the answer says what happened to the
 * message, and the addresses stay where the audit rules keep them (SEC-03, DB-04). The history is the
 * canonical transitions of §6.3 in the order they occurred, which is what a source system needs to see
 * why a message ended where it did.
 *
 * @param reason set for every non-delivery status (ST-01)
 * @param batchId {@code null} for a single message
 */
public record MessageView(
        MessageId messageId,
        StreamId streamId,
        ExternalMessageId externalMessageId,
        BatchId batchId,
        MessageStatus status,
        RejectionReason reason,
        Delivery delivery,
        List<Transition> history) {

    public MessageView {
        Guard.notNull(messageId, "MessageView.messageId");
        Guard.notNull(streamId, "MessageView.streamId");
        Guard.notNull(externalMessageId, "MessageView.externalMessageId");
        Guard.notNull(status, "MessageView.status");
        Guard.notNull(delivery, "MessageView.delivery");
        history = Guard.copyOf(history);
    }

    public Optional<RejectionReason> reasonOptional() {
        return Optional.ofNullable(reason);
    }

    /**
     * How the message was routed and what it cost (FR-2.1, FR-6.2).
     *
     * @param channel {@code null} until routing picked one (MP-03)
     * @param segments SMS segments the message was split into; 0 for other channels (§18.3)
     * @param terminalAt {@code null} while the message is still in flight
     */
    public record Delivery(
            Channel channel,
            ProviderCode provider,
            int segments,
            Money cost,
            Instant acceptedAt,
            Instant terminalAt,
            CorrelationId correlationId,
            boolean test) {

        public Delivery {
            Guard.notNegative(segments, "Delivery.segments");
            Guard.notNull(acceptedAt, "Delivery.acceptedAt");
            Guard.notNull(correlationId, "Delivery.correlationId");
        }

        public Optional<Channel> channelOptional() {
            return Optional.ofNullable(channel);
        }

        public Optional<ProviderCode> providerOptional() {
            return Optional.ofNullable(provider);
        }

        public Optional<Money> costOptional() {
            return Optional.ofNullable(cost);
        }

        public Optional<Instant> terminalAtOptional() {
            return Optional.ofNullable(terminalAt);
        }
    }

    /** One canonical transition of the message (§6.3, ST-01). */
    public record Transition(MessageStatus status, RejectionReason reason, String detail, Instant occurredAt) {

        public Transition {
            Guard.notNull(status, "Transition.status");
            Guard.notNull(occurredAt, "Transition.occurredAt");
        }
    }
}
