package uz.hamkorbank.commhub.domain.model;

import java.time.Instant;
import java.util.Optional;
import uz.hamkorbank.commhub.domain.model.type.RejectionReason;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * A message that stayed undelivered after all attempts (§6.1, §10.1 {@code dlq_entry}, FR-3.3).
 *
 * <p>Keyed by the message it belongs to. Operators may retry it once — the message then goes back to
 * {@code QUEUED} with a new delivery attempt (ST-02) — or archive it.
 */
public final class DlqEntry extends AggregateRoot<MessageId> {

    public static final int MAX_LAST_ERROR_LENGTH = 2048;

    private final RejectionReason reason;
    private final String lastError;
    private final Instant movedAt;

    private String retriedBy;
    private Instant retriedAt;
    private boolean archived;

    private DlqEntry(MessageId messageId, RejectionReason reason, String lastError, Instant movedAt) {
        super(messageId);
        this.reason = Guard.notNull(reason, "DlqEntry.reason");
        this.lastError = Guard.maxLength(lastError, MAX_LAST_ERROR_LENGTH, "DlqEntry.lastError");
        this.movedAt = Guard.notNull(movedAt, "DlqEntry.movedAt");
    }

    public static DlqEntry of(MessageId messageId, RejectionReason reason, String lastError, Instant movedAt) {
        return new DlqEntry(messageId, reason, lastError, movedAt);
    }

    public MessageId messageId() {
        return id();
    }

    /** Whether an operator may still retry this entry (FR-3.3). */
    public boolean isRetryable() {
        return !archived && retriedAt == null;
    }

    /** Marks the entry as manually retried (FR-3.3); the message itself is requeued by the use case. */
    public void retry(String operator, Instant retriedAt) {
        Guard.notBlank(operator, "operator");
        Guard.notNull(retriedAt, "retriedAt");
        Guard.isTrue(!archived, "an archived DLQ entry cannot be retried");
        Guard.isTrue(this.retriedAt == null, "DLQ entry has already been retried");
        this.retriedBy = operator;
        this.retriedAt = retriedAt;
    }

    /** Writes the entry off to the archive (FR-3.3). */
    public void archive() {
        this.archived = true;
    }

    public RejectionReason reason() {
        return reason;
    }

    public Optional<String> lastError() {
        return Optional.ofNullable(lastError);
    }

    public Instant movedAt() {
        return movedAt;
    }

    public Optional<String> retriedBy() {
        return Optional.ofNullable(retriedBy);
    }

    public Optional<Instant> retriedAt() {
        return Optional.ofNullable(retriedAt);
    }

    public boolean isArchived() {
        return archived;
    }
}
