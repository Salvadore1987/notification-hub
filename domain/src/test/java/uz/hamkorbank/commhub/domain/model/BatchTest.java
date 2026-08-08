package uz.hamkorbank.commhub.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static uz.hamkorbank.commhub.domain.DomainFixtures.NOW;
import static uz.hamkorbank.commhub.domain.DomainFixtures.STREAM_ID;
import static uz.hamkorbank.commhub.domain.DomainFixtures.uzs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.hamkorbank.commhub.domain.exception.DomainValidationException;
import uz.hamkorbank.commhub.domain.exception.InvalidStatusTransitionException;
import uz.hamkorbank.commhub.domain.model.type.BatchStatus;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.vo.BatchId;

/** Batch aggregate: chunked upload, progress and operator control (FR-1.6, FR-3.1, FR-3.2). */
class BatchTest {

    @Test
    @DisplayName("FR-1.6: a batch is visible from acceptance and grows while items are uploaded")
    void itemsMayArriveInChunks() {
        // Arrange
        Batch batch = newBatch(0L);

        // Act
        batch.addItems(500L);
        batch.addItems(500L);

        // Assert
        assertThat(batch.status()).isEqualTo(BatchStatus.ACCEPTED);
        assertThat(batch.total()).isEqualTo(1_000L);
        assertThat(batch.isDispatchable()).isTrue();
    }

    @Test
    @DisplayName("FR-3.2: pause, resume and stop follow the batch state machine")
    void operatorControlFollowsTheStateMachine() {
        // Arrange
        Batch batch = newBatch(10L);

        // Act
        batch.startProcessing();
        batch.pause();

        // Assert
        assertThat(batch.status()).isEqualTo(BatchStatus.PAUSED);
        assertThat(batch.isDispatchable()).isFalse();

        batch.resume();
        assertThat(batch.status()).isEqualTo(BatchStatus.PROCESSING);

        batch.stop();
        assertThat(batch.status()).isEqualTo(BatchStatus.STOPPED);
        assertThat(batch.status().isTerminal()).isTrue();
    }

    @Test
    @DisplayName("a stopped batch cannot be resumed or extended")
    void terminalBatchIsClosed() {
        // Arrange
        Batch batch = newBatch(10L);
        batch.stop();

        // Act + Assert
        assertThatExceptionOfType(InvalidStatusTransitionException.class)
                .isThrownBy(batch::resume)
                .withMessageContaining("STOPPED -> PROCESSING");
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> batch.addItems(1L))
                .withMessageContaining("cannot add items");
    }

    @Test
    @DisplayName("FR-3.1: progress carries completion and delivery rate")
    void progressIsAggregated() {
        // Arrange
        Batch batch = newBatch(200L);
        batch.startProcessing();

        // Act
        batch.registerProcessed(100L);
        batch.registerSent(100L);
        batch.registerDelivered(75L);
        batch.registerFailed(25L);
        batch.applyCostEstimate(uzs("5000"));

        // Assert
        Batch.Progress progress = batch.progress();
        assertThat(progress.total()).isEqualTo(200L);
        assertThat(progress.processed()).isEqualTo(100L);
        assertThat(progress.sent()).isEqualTo(100L);
        assertThat(progress.completionPercent()).isEqualTo(50.0d);
        assertThat(progress.deliveryRate()).isEqualTo(0.75d);
        assertThat(progress.remaining()).isEqualTo(100L);
        assertThat(batch.isFullyProcessed()).isFalse();
        assertThat(batch.costEstimate()).contains(uzs("5000"));

        batch.registerProcessed(100L);
        assertThat(batch.isFullyProcessed()).isTrue();
        batch.complete();
        assertThat(batch.status()).isEqualTo(BatchStatus.COMPLETED);
    }

    @Test
    @DisplayName("an empty progress reports zero instead of dividing by zero")
    void emptyProgressIsSafe() {
        // Act
        Batch.Progress progress = new Batch.Progress(0L, 0L, 0L, 0L, 0L);

        // Assert
        assertThat(progress.completionPercent()).isZero();
        assertThat(progress.deliveryRate()).isZero();
        assertThat(progress.remaining()).isZero();
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> new Batch.Progress(-1L, 0L, 0L, 0L, 0L));
    }

    @Test
    @DisplayName("negative counter increments are refused")
    void countersRejectNegativeIncrements() {
        // Arrange
        Batch batch = newBatch(10L);

        // Act + Assert
        assertThatExceptionOfType(DomainValidationException.class).isThrownBy(() -> batch.registerSent(-1L));
        assertThat(batch.streamId()).isEqualTo(STREAM_ID);
        assertThat(batch.channel()).isEqualTo(Channel.SMS);
        assertThat(batch.createdAt()).isEqualTo(NOW);
        assertThat(batch.timing()).isEqualTo(Timing.immediate());
    }

    private static Batch newBatch(long total) {
        return Batch.accept(BatchId.newId(), STREAM_ID, Channel.SMS, total, Timing.immediate(), NOW);
    }
}
