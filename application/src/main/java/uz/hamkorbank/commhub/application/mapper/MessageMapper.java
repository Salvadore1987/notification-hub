package uz.hamkorbank.commhub.application.mapper;

import org.mapstruct.Mapper;
import uz.hamkorbank.commhub.application.dto.MessageKey;
import uz.hamkorbank.commhub.application.dto.MessageStatusEvent;
import uz.hamkorbank.commhub.application.dto.SubmitMessageResult;
import uz.hamkorbank.commhub.domain.model.Message;
import uz.hamkorbank.commhub.domain.model.MessageEnvelope;
import uz.hamkorbank.commhub.domain.model.StatusChange;
import uz.hamkorbank.commhub.domain.support.UuidV7;

/**
 * Conversions of the {@code Message} aggregate into the outbound contracts (§6.4, IR-01).
 *
 * <p>Written as default methods: the aggregate exposes fluent accessors and {@code Optional} state,
 * which the MapStruct generator cannot introspect. Keeping them here still holds the project rule
 * that no use case contains mapping logic.
 */
@Mapper(componentModel = "spring")
public interface MessageMapper {

    /** Identification block of an outbound event (§6.4). */
    default MessageKey toKey(Message message) {
        MessageEnvelope envelope = message.envelope();
        return new MessageKey(
                envelope.streamId(),
                envelope.batchId(),
                envelope.id(),
                envelope.externalId(),
                envelope.correlationId());
    }

    /**
     * Canonical status event for the source systems (§6.4, MP-04).
     *
     * <p>The event id is a UUIDv7, which gives consumers a time-ordered idempotency key (AD-03).
     */
    default MessageStatusEvent toStatusEvent(Message message, StatusChange change) {
        return new MessageStatusEvent(
                UuidV7.generate(),
                change.occurredAt(),
                toKey(message),
                message.selectedChannel().orElse(null),
                change.providerCode(),
                change.status(),
                change.details(),
                toReason(change),
                message.segments());
    }

    /** Result returned to the caller of {@code SubmitMessage} (FR-1.1, FR-1.4). */
    default SubmitMessageResult toSubmitResult(Message message) {
        return message.statusReason()
                .map(reason -> new SubmitMessageResult(
                        message.id(),
                        message.status(),
                        reason,
                        message.statusHistory().getLast().details(),
                        message.duplicateOf().orElse(null)))
                .orElseGet(() -> SubmitMessageResult.accepted(message.id(), message.status()));
    }

    private static MessageStatusEvent.StatusReason toReason(StatusChange change) {
        return change.reasonOptional()
                .map(reason -> MessageStatusEvent.StatusReason.of(reason, change.details()))
                .orElse(null);
    }
}
