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
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import uz.hamkorbank.commhub.application.port.in.command.SubmitMessageCommand;
import uz.hamkorbank.commhub.application.port.out.AuditEntry;
import uz.hamkorbank.commhub.application.port.out.AuditPort;
import uz.hamkorbank.commhub.application.port.out.BatchRepository;
import uz.hamkorbank.commhub.application.port.out.ClockPort;
import uz.hamkorbank.commhub.application.port.out.StreamRepository;
import uz.hamkorbank.commhub.domain.model.Actor;
import uz.hamkorbank.commhub.domain.model.Batch;
import uz.hamkorbank.commhub.domain.model.TemplateRef;
import uz.hamkorbank.commhub.domain.model.type.BatchStatus;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.ContentLocale;
import uz.hamkorbank.commhub.domain.model.type.MessageStatus;
import uz.hamkorbank.commhub.domain.model.type.RejectionReason;
import uz.hamkorbank.commhub.domain.model.vo.BatchId;
import uz.hamkorbank.commhub.domain.model.vo.ExternalMessageId;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;
import uz.hamkorbank.commhub.domain.model.vo.TemplateCode;

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
        // Заголовок объявил 10, приехало 2 — рассылка остаётся десятью, а не становится двенадцатью:
        // объявленное и загруженное — не слагаемые (FR-1.6)
        assertThat(batch.total()).isEqualTo(10L);
        assertThat(batch.status()).isEqualTo(BatchStatus.PROCESSING);
        verify(submitMessage, org.mockito.Mockito.times(2)).submit(any());
    }

    @Test
    @DisplayName("FR-1.6: an item is rendered from the header template with its own merge values")
    void laysItemVariablesOverTheHeaderTemplate() {
        // Arrange — так выглядит документированная заливка: шаблон назван один раз в заголовке,
        // элемент несёт только переменные своей строки
        Batch batch = existingBatch();
        batch.applyItemDefaults(new Batch.ItemDefaults(
                null, TemplateRef.of(TemplateCode.of("OTP_RU_UZ"), ContentLocale.RU, Map.of("BANK", "Hamkor")), false));
        AddBatchItemsCommand command = new AddBatchItemsCommand(
                batch.id(),
                STREAM_ID,
                List.of(new AddBatchItemsCommand.Item(
                        ExternalMessageId.of("i-1"), recipient(), null, null, Map.of("CODE", "1234"), null)));

        // Act
        submitBatch.addItems(command);

        // Assert — код и локаль пришли из заголовка, переменные строки легли поверх его собственных
        ArgumentCaptor<SubmitMessageCommand> submitted = ArgumentCaptor.forClass(SubmitMessageCommand.class);
        verify(submitMessage).submit(submitted.capture());
        TemplateRef template = submitted.getValue().template();
        assertThat(template.code().value()).isEqualTo("OTP_RU_UZ");
        assertThat(template.locale()).isEqualTo(ContentLocale.RU);
        assertThat(template.variables()).containsExactlyInAnyOrderEntriesOf(Map.of("BANK", "Hamkor", "CODE", "1234"));
    }

    @Test
    @DisplayName("FR-1.6: an item naming its own template still keeps its merge values")
    void keepsTheVariablesOfAnItemThatOverridesTheTemplate() {
        // Arrange
        Batch batch = existingBatch();
        batch.applyItemDefaults(
                new Batch.ItemDefaults(null, TemplateRef.of(TemplateCode.of("OTP_RU_UZ"), ContentLocale.RU), false));
        AddBatchItemsCommand command = new AddBatchItemsCommand(
                batch.id(),
                STREAM_ID,
                List.of(new AddBatchItemsCommand.Item(
                        ExternalMessageId.of("i-1"),
                        recipient(),
                        null,
                        TemplateRef.of(TemplateCode.of("ONLY_RU"), ContentLocale.UZ),
                        Map.of("VALUE", "42"),
                        null)));

        // Act
        submitBatch.addItems(command);

        // Assert
        ArgumentCaptor<SubmitMessageCommand> submitted = ArgumentCaptor.forClass(SubmitMessageCommand.class);
        verify(submitMessage).submit(submitted.capture());
        assertThat(submitted.getValue().template().code().value()).isEqualTo("ONLY_RU");
        assertThat(submitted.getValue().template().variables()).containsEntry("VALUE", "42");
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
    @DisplayName("FR-3.1: a batch consumed entirely on the way in closes itself")
    void closesABatchThatProducedNoMessages() {
        // Arrange — повторная загрузка того же файла в окне дедупликации: все строки DUPLICATE
        Batch batch = fullyAnnouncedBatch(2L);
        when(submitMessage.submit(any())).thenReturn(SubmitMessageResult.duplicate(MessageId.newId()));

        // Act
        submitBatch.addItems(itemsCommand(batch.id(), "i-1", "i-2"));

        // Assert — сообщений у рассылки нет, значит закрыть её больше некому: BatchProgressRecorder
        // считает по терминальным статусам сообщений, а их не появилось ни одного
        assertThat(batch.progress().processed()).isEqualTo(2L);
        assertThat(batch.status()).isEqualTo(BatchStatus.COMPLETED);
    }

    @Test
    @DisplayName("FR-1.6: a batch still owed chunks is not closed by one that ended in duplicates")
    void keepsABatchOpenWhileItsAnnouncedItemsAreStillComing() {
        // Arrange — заголовок объявил 10, приехали первые два, и оба дубли
        Batch batch = existingBatch();
        when(submitMessage.submit(any())).thenReturn(SubmitMessageResult.duplicate(MessageId.newId()));

        // Act
        submitBatch.addItems(itemsCommand(batch.id(), "i-1", "i-2"));

        // Assert — закрыть её здесь значило бы отказать следующему чанку
        assertThat(batch.status()).isEqualTo(BatchStatus.PROCESSING);
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
    @DisplayName("FR-7.3: the justification the operator typed reaches the journal as its own field")
    void journalsTheJustification() {
        // Arrange
        Batch batch = existingBatch();
        BatchActionCommand command =
                new BatchActionCommand(batch.id(), Actor.operator("ivanov"), "провайдер лежит, ждём");

        // Act
        control.pause(command);

        // Assert
        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).write(entry.capture());
        assertThat(entry.getValue().reason()).isEqualTo("провайдер лежит, ждём");
        assertThat(entry.getValue().after()).isEqualTo(BatchStatus.PAUSED.name());
        assertThat(entry.getValue().before()).isNotEqualTo(entry.getValue().after());
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
        return acceptedBatch(10L);
    }

    /** Рассылка, у которой объявленное количество совпадёт с загруженным, — так шлёт панель. */
    private Batch fullyAnnouncedBatch(long announced) {
        return acceptedBatch(announced);
    }

    private Batch acceptedBatch(long announced) {
        Batch batch = Batch.accept(
                BatchId.newId(),
                STREAM_ID,
                Channel.SMS,
                announced,
                uz.hamkorbank.commhub.domain.model.Timing.immediate(),
                NOW);
        when(batches.findById(batch.id())).thenReturn(Optional.of(batch));
        return batch;
    }

    private static AddBatchItemsCommand itemsCommand(BatchId batchId, String... externalIds) {
        List<AddBatchItemsCommand.Item> items = java.util.Arrays.stream(externalIds)
                .map(id -> new AddBatchItemsCommand.Item(
                        ExternalMessageId.of(id), recipient(), smsContents(), null, Map.of(), null))
                .toList();
        return new AddBatchItemsCommand(batchId, STREAM_ID, items);
    }
}
