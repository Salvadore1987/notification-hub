package uz.hamkorbank.commhub.application.dto;

import java.time.Instant;
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
 * One line of the message list of the admin panel (§11.2 "Сообщения").
 *
 * <p>Unlike {@link MessageView}, which answers a source system asking about its own submission, this one
 * carries the recipient: the operator screen is where the address is the point. It travels unmasked out
 * of the application layer — the layer knows nothing about roles — and the adapter decides how much of
 * it a given role is shown (SEC-06, DB-04).
 */
public record MessageDigestView(
        MessageId messageId,
        StreamId streamId,
        ExternalMessageId externalMessageId,
        Channel channel,
        MessageStatus status,
        String recipient,
        Instant acceptedAt,
        Routing routing) {

    public MessageDigestView {
        Guard.notNull(messageId, "MessageDigestView.messageId");
        Guard.notNull(status, "MessageDigestView.status");
        Guard.notNull(acceptedAt, "MessageDigestView.acceptedAt");
        Guard.notNull(routing, "MessageDigestView.routing");
    }

    /** How the line was routed and what it cost (FR-2.1, FR-6.2). */
    public record Routing(
            ProviderCode provider,
            Channel channel,
            RejectionReason reason,
            BatchId batchId,
            CorrelationId correlationId,
            Money cost,
            int segments,
            Instant terminalAt) {

        public Routing {
            Guard.notNegative(segments, "Routing.segments");
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
}
