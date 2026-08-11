package uz.hamkorbank.commhub.application.service.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.NOW;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import uz.hamkorbank.commhub.application.ApplicationFixtures;
import uz.hamkorbank.commhub.application.port.out.BatchRepository;
import uz.hamkorbank.commhub.domain.model.Actor;
import uz.hamkorbank.commhub.domain.model.Batch;
import uz.hamkorbank.commhub.domain.model.ChannelPlan;
import uz.hamkorbank.commhub.domain.model.Message;
import uz.hamkorbank.commhub.domain.model.MessageEnvelope;
import uz.hamkorbank.commhub.domain.model.Timing;
import uz.hamkorbank.commhub.domain.model.content.MessageContents;
import uz.hamkorbank.commhub.domain.model.content.SmsContent;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.Priority;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;
import uz.hamkorbank.commhub.domain.model.vo.BatchId;
import uz.hamkorbank.commhub.domain.model.vo.CorrelationId;
import uz.hamkorbank.commhub.domain.model.vo.DedupKey;
import uz.hamkorbank.commhub.domain.model.vo.ExternalMessageId;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;
import uz.hamkorbank.commhub.domain.model.vo.Recipient;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;

/**
 * The counters of a batch, and the three ways a naive implementation double-counts (FR-3.1, ADR-0040).
 *
 * <p>Each of these was a case somebody would have had to remember with "increment here" code. With the
 * delta being the difference of two contributions, none of them needs remembering — which is what these
 * tests are really asserting.
 */
class BatchProgressRecorderTest {

    private static final BatchId BATCH_ID = BatchId.newId();

    private BatchRepository batches;
    private BatchProgressRecorder recorder;

    @BeforeEach
    void setUp() {
        batches = mock(BatchRepository.class);
        when(batches.applyProgress(any(), any())).thenReturn(new Batch.Progress(10, 1, 1, 0, 0));
        recorder = new BatchProgressRecorder(batches);
    }

    @Test
    @DisplayName("a message handed to a provider counts as sent and, for SMS, not yet as processed")
    void countsTheSend() {
        // Arrange
        Message message = batchMessage();
        BatchProgressRecorder.Contribution before = recorder.contributionOf(message);
        reachProvider(message);

        // Act
        recorder.apply(message, before);

        // Assert
        assertThat(delta()).isEqualTo(new Batch.Delta(0, 1, 0, 0));
    }

    @Test
    @DisplayName("AD-06: a provider report delivered twice moves no counter")
    void repeatedReportCountsOnce() {
        // Arrange — первый отчёт учтён
        Message message = batchMessage();
        reachProvider(message);
        BatchProgressRecorder.Contribution afterSend = recorder.contributionOf(message);
        message.markDelivered("DLVRD", Actor.provider("PLAYMOBILE"), NOW.plusSeconds(30));
        recorder.apply(message, afterSend);
        BatchProgressRecorder.Contribution afterDelivery = recorder.contributionOf(message);

        // Act — повторный отчёт ничего в сообщении не меняет
        recorder.apply(message, afterDelivery);

        // Assert — вклад тот же, значит дельта нулевая и записи не было
        verify(batches, org.mockito.Mockito.times(1)).applyProgress(any(), any());
    }

    @Test
    @DisplayName("SG-03: a re-send after a reconciliation counts the message as sent only once")
    void resendAfterRetryCountsSentOnce() {
        // Arrange — ушло провайдеру, вернулось в RETRYING, ушло снова
        Message message = batchMessage();
        reachProvider(message);
        message.markRetrying("no report, re-sending", Actor.system(), NOW.plusSeconds(60));
        BatchProgressRecorder.Contribution before = recorder.contributionOf(message);
        message.markSending(Actor.system(), NOW.plusSeconds(61));
        message.markSentToProvider("ACCEPTD", Actor.provider("PLAYMOBILE"), NOW.plusSeconds(62));

        // Act
        recorder.apply(message, before);

        // Assert — sent читается из истории, поэтому вторая отправка его не удваивает
        verify(batches, never()).applyProgress(eq(BATCH_ID), eq(new Batch.Delta(0, 1, 0, 0)));
    }

    @Test
    @DisplayName("FR-3.3: a DLQ retry takes the message back out of failed and processed")
    void dlqRetrySubtracts() {
        // Arrange — сообщение упало и было учтено как failed + processed
        Message message = batchMessage();
        reachProvider(message);
        message.markRetrying("no answer", Actor.system(), NOW.plusSeconds(80));
        message.markFailed("attempts exhausted", Actor.system(), NOW.plusSeconds(90));
        BatchProgressRecorder.Contribution failed = recorder.contributionOf(message);

        // Act — оператор повторяет его из DLQ
        message.requeueFromDlq(Actor.operator("ivanov"), NOW.plusSeconds(120));
        recorder.apply(message, failed);

        // Assert — отрицательная дельта получается из арифметики, а не из компенсирующего вызова
        assertThat(delta()).isEqualTo(new Batch.Delta(-1, 0, 0, -1));
    }

    @Test
    @DisplayName("a message that belongs to no batch touches no counters")
    void ignoresMessagesOutsideABatch() {
        // Arrange
        Message message = ApplicationFixtures.smsMessage();
        BatchProgressRecorder.Contribution before = recorder.contributionOf(message);
        reachProvider(message);

        // Act
        recorder.apply(message, before);

        // Assert
        verify(batches, never()).applyProgress(any(), any());
    }

    @Test
    @DisplayName("FR-3.1: the batch is closed once, when its last message is processed")
    void closesTheBatchWhenComplete() {
        // Arrange
        when(batches.applyProgress(any(), any())).thenReturn(new Batch.Progress(10, 10, 10, 9, 1));
        Message message = batchMessage();
        reachProvider(message);
        BatchProgressRecorder.Contribution before = recorder.contributionOf(message);
        message.markDelivered("DLVRD", Actor.provider("PLAYMOBILE"), NOW.plusSeconds(30));

        // Act
        recorder.apply(message, before);

        // Assert
        verify(batches).markCompleted(BATCH_ID);
    }

    private Batch.Delta delta() {
        ArgumentCaptor<Batch.Delta> captor = ArgumentCaptor.forClass(Batch.Delta.class);
        verify(batches).applyProgress(eq(BATCH_ID), captor.capture());
        return captor.getValue();
    }

    private static void reachProvider(Message message) {
        message.markValidated(Actor.system(), NOW);
        message.markRouted(
                Channel.SMS, ApplicationFixtures.smsProvider("PLAYMOBILE").ref(), Actor.system(), NOW);
        message.markQueued(Actor.system(), NOW);
        message.markSending(Actor.system(), NOW);
        message.markSentToProvider("ACCEPTD", Actor.provider("PLAYMOBILE"), NOW.plusSeconds(1));
    }

    private static Message batchMessage() {
        MessageEnvelope envelope = new MessageEnvelope(
                MessageId.newId(),
                ExternalMessageId.of("item-1"),
                StreamId.of("payroll"),
                BATCH_ID,
                TrafficClass.NOTIFICATION,
                Priority.NORMAL,
                DedupKey.of("payroll:item-1"),
                CorrelationId.newId());
        return Message.accept(
                envelope,
                Recipient.ofMsisdn(uz.hamkorbank.commhub.domain.model.vo.Msisdn.of("998901234567")),
                ChannelPlan.explicitChannel(Channel.SMS),
                MessageContents.of(SmsContent.of("Зарплата зачислена")),
                null,
                Timing.immediate(),
                NOW);
    }
}
