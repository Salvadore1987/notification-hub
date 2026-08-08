package uz.hamkorbank.commhub.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static uz.hamkorbank.commhub.domain.DomainFixtures.NOW;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.hamkorbank.commhub.domain.exception.DomainValidationException;
import uz.hamkorbank.commhub.domain.model.type.RejectionReason;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;

/** DLQ entry: single manual retry or archiving (FR-3.3). */
class DlqEntryTest {

    @Test
    @DisplayName("FR-3.3: a fresh entry is retryable and keeps the last provider error")
    void freshEntryIsRetryable() {
        // Act
        DlqEntry entry = newEntry();

        // Assert
        assertThat(entry.isRetryable()).isTrue();
        assertThat(entry.isArchived()).isFalse();
        assertThat(entry.reason()).isEqualTo(RejectionReason.ATTEMPTS_EXHAUSTED);
        assertThat(entry.lastError()).contains("Playmobile 100 Internal server error");
        assertThat(entry.movedAt()).isEqualTo(NOW);
        assertThat(entry.retriedBy()).isEmpty();
        assertThat(entry.retriedAt()).isEmpty();
        assertThat(entry.messageId()).isEqualTo(entry.id());
    }

    @Test
    @DisplayName("FR-3.3: an entry can be retried once")
    void retryIsRecordedOnce() {
        // Arrange
        DlqEntry entry = newEntry();

        // Act
        entry.retry("operator-1", NOW.plusSeconds(600));

        // Assert
        assertThat(entry.retriedBy()).contains("operator-1");
        assertThat(entry.retriedAt()).contains(NOW.plusSeconds(600));
        assertThat(entry.isRetryable()).isFalse();
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> entry.retry("operator-2", NOW.plusSeconds(700)))
                .withMessageContaining("already been retried");
    }

    @Test
    @DisplayName("FR-3.3: an archived entry cannot be retried")
    void archivedEntryCannotBeRetried() {
        // Arrange
        DlqEntry entry = newEntry();

        // Act
        entry.archive();

        // Assert
        assertThat(entry.isArchived()).isTrue();
        assertThat(entry.isRetryable()).isFalse();
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> entry.retry("operator-1", NOW))
                .withMessageContaining("archived");
    }

    @Test
    @DisplayName("reason, moved-at and the operator name are required")
    void invariantsAreEnforced() {
        // Act + Assert
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> DlqEntry.of(MessageId.newId(), null, "error", NOW));
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> DlqEntry.of(MessageId.newId(), RejectionReason.ATTEMPTS_EXHAUSTED, "error", null));
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> newEntry().retry(" ", NOW));
    }

    private static DlqEntry newEntry() {
        return DlqEntry.of(
                MessageId.newId(), RejectionReason.ATTEMPTS_EXHAUSTED, "Playmobile 100 Internal server error", NOW);
    }
}
