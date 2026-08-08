package uz.hamkorbank.commhub.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.NOW;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.STREAM_ID;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.recipient;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.smsContents;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.stream;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.hamkorbank.commhub.application.dto.BatchAcceptedResult;
import uz.hamkorbank.commhub.application.dto.BatchControlResult;
import uz.hamkorbank.commhub.application.dto.BatchItemsResult;
import uz.hamkorbank.commhub.application.dto.SubmitMessageResult;
import uz.hamkorbank.commhub.application.exception.NotFoundException;
import uz.hamkorbank.commhub.application.mapper.BatchMapper;
import uz.hamkorbank.commhub.application.mapper.BatchMapperImpl;
import uz.hamkorbank.commhub.application.port.in.SubmitMessage;
import uz.hamkorbank.commhub.application.port.in.command.AddBatchItemsCommand;
import uz.hamkorbank.commhub.application.port.in.command.BatchActionCommand;
import uz.hamkorbank.commhub.application.port.in.command.CreateBatchCommand;
import uz.hamkorbank.commhub.application.port.out.AuditEntry;
import uz.hamkorbank.commhub.application.port.out.AuditPort;
import uz.hamkorbank.commhub.application.port.out.BatchRepository;
import uz.hamkorbank.commhub.application.port.out.ClockPort;
import uz.hamkorbank.commhub.application.port.out.StreamRepository;
import uz.hamkorbank.commhub.domain.model.Actor;
import uz.hamkorbank.commhub.domain.model.Batch;
import uz.hamkorbank.commhub.domain.model.type.BatchStatus;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.MessageStatus;
import uz.hamkorbank.commhub.domain.model.type.RejectionReason;
import uz.hamkorbank.commhub.domain.model.vo.BatchId;
import uz.hamkorbank.commhub.domain.model.vo.ExternalMessageId;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;

/** Batch acceptance, chunked item upload and operator control (FR-1.6, FR-3.1, FR-3.2). */
class BatchLifecycleTest {

    private ClockPort clock;
    private BatchRepository batches;
    private StreamRepository streams;
    private SubmitMessage submitMessage;
    private AuditPort audit;
    private BatchMapper mapper;

    private SubmitBatchService submitBatch;
    private BatchControlService control;

    @BeforeEach
    void setUp() {
        clock = mock(ClockPort.class);
        batches = mock(BatchRepository.class);
        streams = mock(StreamRepository.class);
        submitMessage = mock(SubmitMessage.class);
        audit = mock(AuditPort.class);
        mapper = new BatchMapperImpl();

        when(clock.now()).thenReturn(NOW);
        when(streams.findById(STREAM_ID)).thenReturn(Optional.of(stream()));
        when(batches.save(any(Batch.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(submitMessage.submit(any()))
                .thenReturn(SubmitMessageResult.accepted(MessageId.newId(), MessageStatus.QUEUED));

        submitBatch = new SubmitBatchService(clock, batches, streams, submitMessage, mapper);
        control = new BatchControlService(clock, batches, audit, mapper);
    }

    @Test
    @DisplayName("FR-1.6: the header makes the batch visible before any item is uploaded")
    void acceptsBatchHeader() {
        // Arrange + Act
        BatchAcceptedResult result = submitBatch.create(CreateBatchCommand.of(STREAM_ID, Channel.SMS, 1000L));

        // Assert
        assertThat(result.status()).isEqualTo(BatchStatus.ACCEPTED);
        assertThat(result.total()).isEqualTo(1000L);
        verify(batches).save(any(Batch.class));
    }

    @Test
    @DisplayName("FR-1.6: each item of a chunk goes through the normal submission pipeline")
    void expandsItemsIntoSubmissions() {
        // Arrange
        Batch batch = existingBatch();

        // Act
        BatchItemsResult result = submitBatch.addItems(itemsCommand(batch.id(), "i-1", "i-2"));

        // Assert
        assertThat(result.accepted()).isEqualTo(2L);
        assertThat(result.rejected()).isZero();
        assertThat(batch.total()).isEqualTo(12L);
        assertThat(batch.status()).isEqualTo(BatchStatus.PROCESSING);
        verify(submitMessage, org.mockito.Mockito.times(2)).submit(any());
    }

    @Test
    @DisplayName("FR-1.4: a rejected item is reported on its own and does not fail the chunk")
    void reportsItemRejectionsIndividually() {
        // Arrange
        Batch batch = existingBatch();
        when(submitMessage.submit(any()))
                .thenReturn(SubmitMessageResult.rejected(MessageId.newId(), RejectionReason.SUPPRESSED, "opt-out"))
                .thenReturn(SubmitMessageResult.accepted(MessageId.newId(), MessageStatus.QUEUED));

        // Act
        BatchItemsResult result = submitBatch.addItems(itemsCommand(batch.id(), "i-1", "i-2"));

        // Assert
        assertThat(result.accepted()).isEqualTo(1L);
        assertThat(result.rejections()).singleElement().satisfies(rejection -> {
            assertThat(rejection.externalMessageId().value()).isEqualTo("i-1");
            assertThat(rejection.reason()).isEqualTo(RejectionReason.SUPPRESSED);
        });
    }

    @Test
    @DisplayName("FR-1.5: duplicate items are counted separately and never resent")
    void countsDuplicateItems() {
        // Arrange
        Batch batch = existingBatch();
        when(submitMessage.submit(any())).thenReturn(SubmitMessageResult.duplicate(MessageId.newId()));

        // Act
        BatchItemsResult result = submitBatch.addItems(itemsCommand(batch.id(), "i-1"));

        // Assert
        assertThat(result.duplicates()).isEqualTo(1L);
        assertThat(result.accepted()).isZero();
    }

    @Test
    @DisplayName("FR-3.2: pause, resume and stop change the batch state and are audited")
    void controlsBatchLifecycle() {
        // Arrange
        Batch batch = existingBatch();
        BatchActionCommand command = BatchActionCommand.of(batch.id(), Actor.operator("ivanov"));

        // Act
        BatchControlResult paused = control.pause(command);
        BatchControlResult resumed = control.resume(command);
        BatchControlResult stopped = control.stop(command);

        // Assert
        assertThat(paused.status()).isEqualTo(BatchStatus.PAUSED);
        assertThat(resumed.status()).isEqualTo(BatchStatus.PROCESSING);
        assertThat(stopped.status()).isEqualTo(BatchStatus.STOPPED);
        verify(audit, org.mockito.Mockito.times(3)).write(any(AuditEntry.class));
    }

    @Test
    @DisplayName("IR-01: controlling an unknown batch fails with a not-found error")
    void failsOnUnknownBatch() {
        // Arrange
        BatchId unknown = BatchId.newId();
        when(batches.findById(unknown)).thenReturn(Optional.empty());

        // Act + Assert
        assertThatExceptionOfType(NotFoundException.class)
                .isThrownBy(() -> control.pause(BatchActionCommand.of(unknown, Actor.operator("ivanov"))));
    }

    private Batch existingBatch() {
        Batch batch = Batch.accept(
                BatchId.newId(),
                STREAM_ID,
                Channel.SMS,
                10L,
                uz.hamkorbank.commhub.domain.model.Timing.immediate(),
                NOW);
        when(batches.findById(batch.id())).thenReturn(Optional.of(batch));
        return batch;
    }

    private static AddBatchItemsCommand itemsCommand(BatchId batchId, String... externalIds) {
        List<AddBatchItemsCommand.Item> items = java.util.Arrays.stream(externalIds)
                .map(id ->
                        new AddBatchItemsCommand.Item(ExternalMessageId.of(id), recipient(), smsContents(), null, null))
                .toList();
        return new AddBatchItemsCommand(batchId, STREAM_ID, items);
    }
}
