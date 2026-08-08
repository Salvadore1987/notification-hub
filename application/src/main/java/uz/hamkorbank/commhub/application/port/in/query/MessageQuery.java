package uz.hamkorbank.commhub.application.port.in.query;

import java.util.Optional;
import uz.hamkorbank.commhub.domain.model.Actor;
import uz.hamkorbank.commhub.domain.model.vo.ExternalMessageId;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Locates one message, either by the Hub identifier or by the identifier of the source system
 * (§8.2 {@code GET /messages/{id}}, {@code GET /messages?externalMessageId=&streamId=}).
 *
 * <p>The external identifier is unique only inside its stream (FR-1.5), which is why the second form
 * always carries both parts.
 *
 * @param requestedBy who is asking; an operator's read of a customer's message is journalled (SEC-08),
 *     a source system reading the status of its own submission is not. {@code null} means the system.
 */
public record MessageQuery(
        MessageId messageId, StreamId streamId, ExternalMessageId externalMessageId, Actor requestedBy) {

    public MessageQuery {
        Guard.isTrue(
                messageId != null || (streamId != null && externalMessageId != null),
                "MessageQuery requires a messageId or a (streamId, externalMessageId) pair");
        requestedBy = requestedBy == null ? Actor.system() : requestedBy;
    }

    public static MessageQuery byId(MessageId messageId) {
        return byId(messageId, null);
    }

    public static MessageQuery byId(MessageId messageId, Actor requestedBy) {
        Guard.notNull(messageId, "messageId");
        return new MessageQuery(messageId, null, null, requestedBy);
    }

    public static MessageQuery byExternalId(StreamId streamId, ExternalMessageId externalMessageId) {
        return byExternalId(streamId, externalMessageId, null);
    }

    public static MessageQuery byExternalId(StreamId streamId, ExternalMessageId externalMessageId, Actor requestedBy) {
        Guard.notNull(streamId, "streamId");
        Guard.notNull(externalMessageId, "externalMessageId");
        return new MessageQuery(null, streamId, externalMessageId, requestedBy);
    }

    public Optional<MessageId> messageIdOptional() {
        return Optional.ofNullable(messageId);
    }

    /** Description of what was looked for, used in the {@code 404} answer of the adapters. */
    public String describe() {
        return messageId != null ? messageId.toString() : streamId + "/" + externalMessageId;
    }
}
