package uz.hamkorbank.commhub.application.port.out;

import java.time.Instant;
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
 * One row of the message list (§11.2 "Сообщения").
 *
 * <p>A row, not an aggregate. Rebuilding a {@link uz.hamkorbank.commhub.domain.model.Message} per line
 * would mean decrypting its contents (DB-04) and reading its history for a screen that shows neither —
 * fifty times per page, on the screen an operator refreshes while an incident is running.
 *
 * <p>{@code recipient} is carried in clear because that is how it is stored (DB-05) and because the
 * address is what the operator is looking at. Which role sees how much of it is decided where it is
 * written out, in the adapter, exactly as the masking rule of SEC-06 puts it.
 */
public record MessageDigest(
        MessageId messageId,
        StreamId streamId,
        ExternalMessageId externalMessageId,
        Channel channel,
        MessageStatus status,
        String recipient,
        Instant acceptedAt,
        Routing routing) {

    public MessageDigest {
        Guard.notNull(messageId, "MessageDigest.messageId");
        Guard.notNull(streamId, "MessageDigest.streamId");
        Guard.notNull(externalMessageId, "MessageDigest.externalMessageId");
        Guard.notNull(status, "MessageDigest.status");
        Guard.notNull(acceptedAt, "MessageDigest.acceptedAt");
        Guard.notNull(routing, "MessageDigest.routing");
    }

    /**
     * What happened to the row after it was accepted.
     *
     * @param channel {@code null} until routing picked one (MP-03); the outer {@code channel} is the
     *     requested one, this is the one it actually went out on
     * @param terminalAt {@code null} while the message is still in flight
     */
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
    }
}
