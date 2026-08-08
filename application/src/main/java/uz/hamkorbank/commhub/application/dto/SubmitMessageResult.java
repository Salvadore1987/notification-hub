package uz.hamkorbank.commhub.application.dto;

import java.util.Optional;
import uz.hamkorbank.commhub.domain.model.type.MessageStatus;
import uz.hamkorbank.commhub.domain.model.type.RejectionReason;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Outcome of a submission (FR-1.1, FR-1.4, FR-1.5).
 *
 * <p>REST answers 202 with {@code messageId} and the status for an accepted message, and maps
 * {@link #reason()} onto the {@code problem+json} code of IR-01 otherwise. Kafka consumers emit a
 * {@code message.rejected} event with the same reason (FR-1.4).
 *
 * @param messageId {@code null} only when the submission was refused before a message was created,
 *     e.g. for an unknown stream
 * @param duplicateOf message the submission repeats, set when the status is {@code DUPLICATE} (FR-1.5)
 */
public record SubmitMessageResult(
        MessageId messageId, MessageStatus status, RejectionReason reason, String detail, MessageId duplicateOf) {

    public SubmitMessageResult {
        Guard.notNull(status, "SubmitMessageResult.status");
    }

    /** The message entered the pipeline (FR-1.1). */
    public static SubmitMessageResult accepted(MessageId messageId, MessageStatus status) {
        return new SubmitMessageResult(messageId, status, null, null, null);
    }

    /** The submission repeats one inside the dedup window; nothing was sent again (FR-1.5). */
    public static SubmitMessageResult duplicate(MessageId original) {
        Guard.notNull(original, "original");
        return new SubmitMessageResult(
                original, MessageStatus.DUPLICATE, RejectionReason.DUPLICATE_SUBMISSION, null, original);
    }

    /** The submission was refused by validation, filters, quotas or routing (FR-1.4, FR-5.1…FR-5.4). */
    public static SubmitMessageResult rejected(MessageId messageId, RejectionReason reason, String detail) {
        Guard.notNull(reason, "reason");
        return new SubmitMessageResult(messageId, MessageStatus.REJECTED, reason, detail, null);
    }

    public boolean isAccepted() {
        return status != MessageStatus.REJECTED && status != MessageStatus.DUPLICATE;
    }

    public Optional<MessageId> messageIdOptional() {
        return Optional.ofNullable(messageId);
    }

    public Optional<RejectionReason> reasonOptional() {
        return Optional.ofNullable(reason);
    }

    public Optional<MessageId> duplicateOfOptional() {
        return Optional.ofNullable(duplicateOf);
    }
}
