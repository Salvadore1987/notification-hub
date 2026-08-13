package uz.hamkorbank.commhub.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.NOW;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.STREAM_ID;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import uz.hamkorbank.commhub.application.dto.BatchItemsResult;
import uz.hamkorbank.commhub.application.dto.OperatorBatchResult;
import uz.hamkorbank.commhub.application.dto.SubmitMessageResult;
import uz.hamkorbank.commhub.application.exception.ConfigurationConflictException;
import uz.hamkorbank.commhub.application.port.in.SubmitBatch;
import uz.hamkorbank.commhub.application.port.in.SubmitMessage;
import uz.hamkorbank.commhub.application.port.in.command.AddBatchItemsCommand;
import uz.hamkorbank.commhub.application.port.in.command.OperatorBatchCommand;
import uz.hamkorbank.commhub.application.port.in.command.OperatorSendCommand;
import uz.hamkorbank.commhub.application.port.in.command.SubmitMessageCommand;
import uz.hamkorbank.commhub.application.port.out.AuditEntry;
import uz.hamkorbank.commhub.application.port.out.AuditPort;
import uz.hamkorbank.commhub.application.port.out.ClockPort;
import uz.hamkorbank.commhub.application.service.support.PublishedTemplates;
import uz.hamkorbank.commhub.domain.model.Actor;
import uz.hamkorbank.commhub.domain.model.TemplateRef;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.ContentLocale;
import uz.hamkorbank.commhub.domain.model.type.MessageStatus;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;
import uz.hamkorbank.commhub.domain.model.vo.BatchId;
import uz.hamkorbank.commhub.domain.model.vo.ExternalMessageId;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;
import uz.hamkorbank.commhub.domain.model.vo.Msisdn;
import uz.hamkorbank.commhub.domain.model.vo.Recipient;
import uz.hamkorbank.commhub.domain.model.vo.TemplateCode;

/** Sending from the panel: published templates only, a justification, and no test flag (ADR-0038). */
class OperatorSendServiceTest {

    private static final Actor OPERATOR = Actor.operator("a.karimov");
    private static final TemplateRef TEMPLATE = new TemplateRef(TemplateCode.of("PAYROLL"), ContentLocale.RU, Map.of());
    private static final OperatorSendCommand.Target TARGET =
            new OperatorSendCommand.Target(STREAM_ID, Channel.SMS, TrafficClass.NOTIFICATION, null);

    private SubmitMessage submitMessage;
    private SubmitBatch submitBatch;
    private PublishedTemplates templates;
    private AuditPort audit;
    private OperatorSendService service;

    @BeforeEach
    void setUp() {
        submitMessage = mock(SubmitMessage.class);
        submitBatch = mock(SubmitBatch.class);
        templates = mock(PublishedTemplates.class);
        audit = mock(AuditPort.class);
        ClockPort clock = mock(ClockPort.class);
        when(clock.now()).thenReturn(NOW);
        when(submitMessage.submit(any()))
                .thenReturn(SubmitMessageResult.accepted(MessageId.newId(), MessageStatus.QUEUED));
        service = new OperatorSendService(submitMessage, submitBatch, templates, audit, clock);
    }

    @Test
    @DisplayName("ADR-0038: a panel send carries the template, no test flag and no pinned provider")
    void sendsThroughTheOrdinaryPipeline() {
        // Arrange
        OperatorSendCommand command = new OperatorSendCommand(
                OPERATOR,
                "клиент просил продублировать",
                recipient(),
                ExternalMessageId.of("panel-1"),
                TEMPLATE,
                TARGET);

        // Act
        service.send(command);

        // Assert
        ArgumentCaptor<SubmitMessageCommand> submitted = ArgumentCaptor.forClass(SubmitMessageCommand.class);
        verify(submitMessage).submit(submitted.capture());
        SubmitMessageCommand submission = submitted.getValue();
        assertThat(submission.template()).isEqualTo(TEMPLATE);
        assertThat(submission.contents()).as("контент только из шаблона").isNull();
        assertThat(submission.delivery().test()).isFalse();
        assertThat(submission.delivery().pinnedProvider())
                .as("закрепить провайдера вправе только тестовая отправка (FR-2.2)")
                .isNull();
        assertThat(submission.delivery().trafficClass()).isEqualTo(TrafficClass.NOTIFICATION);
    }

    @Test
    @DisplayName("FR-7.3: the justification is journalled before the message is submitted")
    void journalsBeforeSending() {
        // Arrange
        OperatorSendCommand command = new OperatorSendCommand(
                OPERATOR, "инцидент INC-42", recipient(), ExternalMessageId.of("panel-2"), TEMPLATE, TARGET);

        // Act
        service.send(command);

        // Assert — журналируется факт наведения инфраструктуры на живой адрес, независимо от исхода
        InOrder order = inOrder(audit, submitMessage);
        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        order.verify(audit).write(entry.capture());
        order.verify(submitMessage).submit(any());
        assertThat(entry.getValue().reason()).isEqualTo("инцидент INC-42");
        assertThat(entry.getValue().action()).isEqualTo(OperatorSendService.AUDIT_ACTION);
    }

    @Test
    @DisplayName("ADR-0038: an unpublished template stops the send before anything is submitted")
    void refusesAnUnpublishedTemplate() {
        // Arrange
        when(templates.require(any(), any(), any()))
                .thenThrow(new ConfigurationConflictException("template PAYROLL has no published version in RU"));
        OperatorSendCommand command = new OperatorSendCommand(
                OPERATOR, "рассылка", recipient(), ExternalMessageId.of("panel-3"), TEMPLATE, TARGET);

        // Act + Assert
        assertThatExceptionOfType(ConfigurationConflictException.class).isThrownBy(() -> service.send(command));
        verify(submitMessage, never()).submit(any());
        verify(audit, never()).write(any());
    }

    @Test
    @DisplayName("a blank justification is refused by the command itself")
    void requiresAJustification() {
        // Arrange + Act + Assert — обоснование это компонент команды, а не забота контроллера
        assertThatExceptionOfType(RuntimeException.class)
                .isThrownBy(() -> new OperatorSendCommand(
                        OPERATOR, "  ", recipient(), ExternalMessageId.of("panel-4"), TEMPLATE, TARGET));
    }

    @Test
    @DisplayName("FR-1.6: a list is uploaded in chunks, and each row keeps its own merge variables")
    void uploadsTheListInChunks() {
        // Arrange — 501 строка: ровно на одну больше размера чанка
        BatchId batchId = BatchId.newId();
        List<OperatorBatchCommand.Item> items = new java.util.ArrayList<>();
        for (int i = 0; i < 501; i++) {
            items.add(new OperatorBatchCommand.Item(
                    ExternalMessageId.of("row-" + i), recipient(), Map.of("NAME", "Клиент " + i)));
        }
        when(submitBatch.addItems(any()))
                .thenAnswer(invocation -> new BatchItemsResult(
                        batchId,
                        ((AddBatchItemsCommand) invocation.getArgument(0))
                                .items()
                                .size(),
                        0,
                        List.of(),
                        new uz.hamkorbank.commhub.application.dto.BatchProgressDto(501, 0, 0, 0, 0, 0)));

        // Act
        OperatorBatchResult result = service.sendBatch(
                new OperatorBatchCommand(OPERATOR, "зарплатная рассылка", batchId, TEMPLATE, TARGET, items));

        // Assert
        ArgumentCaptor<AddBatchItemsCommand> chunks = ArgumentCaptor.forClass(AddBatchItemsCommand.class);
        verify(submitBatch, org.mockito.Mockito.times(2)).addItems(chunks.capture());
        assertThat(chunks.getAllValues().getFirst().items()).hasSize(500);
        assertThat(chunks.getAllValues().getLast().items()).hasSize(1);
        // Переменные строки едут своим полем, а не внутри её шаблона: элемент рассылки обычно
        // никакого шаблона не называет — его называет заголовок (FR-1.6)
        assertThat(chunks.getAllValues().getFirst().items().getFirst().variables())
                .containsEntry("NAME", "Клиент 0");
        assertThat(chunks.getAllValues()
                        .getFirst()
                        .items()
                        .getFirst()
                        .template()
                        .code())
                .isEqualTo(TEMPLATE.code());
        assertThat(result.accepted()).isEqualTo(501);
    }

    private static Recipient recipient() {
        return Recipient.ofMsisdn(Msisdn.of("998901234567"));
    }
}
