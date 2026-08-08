package uz.hamkorbank.commhub.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static uz.hamkorbank.commhub.domain.DomainFixtures.NOW;
import static uz.hamkorbank.commhub.domain.DomainFixtures.STREAM_ID;
import static uz.hamkorbank.commhub.domain.DomainFixtures.uzs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.hamkorbank.commhub.domain.exception.DomainValidationException;
import uz.hamkorbank.commhub.domain.model.type.BatchStatus;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.vo.BatchId;

/** Reconstitution of a batch read back from storage (§10.1 {@code batch}, FR-3.1). */
class BatchRehydrationTest {

    @Test
    @DisplayName("a rehydrated batch keeps its terminal status and progress counters")
    void rehydrationRestoresTerminalStatusAndCounters() {
        // Arrange
        BatchId id = BatchId.newId();

        // Act
        Batch batch = Batch.rehydrate(id, STREAM_ID, Channel.SMS, Timing.immediate(), NOW)
                .status(BatchStatus.STOPPED)
                .progress(1_000L, 640L, 600L, 580L)
                .failed(40L)
                .costEstimate(uzs("24500.0000"))
                .build();

        // Assert
        assertThat(batch.id()).isEqualTo(id);
        assertThat(batch.status()).isEqualTo(BatchStatus.STOPPED);
        assertThat(batch.total()).isEqualTo(1_000L);
        assertThat(batch.progress()).isEqualTo(new Batch.Progress(1_000L, 640L, 600L, 580L, 40L));
        assertThat(batch.costEstimate()).contains(uzs("24500.0000"));
        assertThat(batch.isDispatchable()).isFalse();
    }

    @Test
    @DisplayName("a rehydrated batch in PROCESSING can still be paused (FR-3.2)")
    void rehydratedBatchStaysControllable() {
        // Arrange
        Batch batch = Batch.rehydrate(BatchId.newId(), STREAM_ID, Channel.SMS, Timing.immediate(), NOW)
                .status(BatchStatus.PROCESSING)
                .progress(10L, 3L, 3L, 2L)
                .build();

        // Act
        batch.pause();

        // Assert
        assertThat(batch.status()).isEqualTo(BatchStatus.PAUSED);
    }

    @Test
    @DisplayName("negative counters are rejected as a corrupted row")
    void rehydrationRejectsNegativeCounters() {
        // Arrange
        Batch.Rehydration corrupted = Batch.rehydrate(BatchId.newId(), STREAM_ID, Channel.SMS, Timing.immediate(), NOW)
                .progress(10L, 3L, 3L, 2L)
                .failed(-1L);

        // Act + Assert
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(corrupted::build)
                .withMessageContaining("Batch.failed");
    }
}
