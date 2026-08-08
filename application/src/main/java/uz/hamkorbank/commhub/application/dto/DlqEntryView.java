package uz.hamkorbank.commhub.application.dto;

import java.time.Instant;
import java.util.Optional;
import uz.hamkorbank.commhub.domain.model.type.RejectionReason;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * One entry of the dead-letter queue as the DLQ screen shows it (§11.2 "DLQ", FR-3.3).
 *
 * @param retriedBy login of whoever retried it; {@code null} while nobody has
 * @param retryable whether the buttons on this row do anything — an entry may be retried once
 */
public record DlqEntryView(
        MessageId messageId,
        RejectionReason reason,
        String lastError,
        Instant movedAt,
        String retriedBy,
        Instant retriedAt,
        boolean archived,
        boolean retryable) {

    public DlqEntryView {
        Guard.notNull(messageId, "DlqEntryView.messageId");
        Guard.notNull(reason, "DlqEntryView.reason");
        Guard.notNull(movedAt, "DlqEntryView.movedAt");
    }

    public Optional<String> lastErrorOptional() {
        return Optional.ofNullable(lastError);
    }

    public Optional<Instant> retriedAtOptional() {
        return Optional.ofNullable(retriedAt);
    }
}
