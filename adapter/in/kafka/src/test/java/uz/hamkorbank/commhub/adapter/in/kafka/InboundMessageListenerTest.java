package uz.hamkorbank.commhub.adapter.in.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uz.hamkorbank.commhub.adapter.in.contract.InboundContractException;
import uz.hamkorbank.commhub.adapter.in.contract.InboundJson;
import uz.hamkorbank.commhub.adapter.in.contract.InboundMessageCodec;
import uz.hamkorbank.commhub.adapter.in.contract.mapper.InboundPayloadMapperImpl;
import uz.hamkorbank.commhub.application.dto.SubmitMessageResult;
import uz.hamkorbank.commhub.application.port.in.SubmitMessage;
import uz.hamkorbank.commhub.application.port.in.command.SubmitMessageCommand;
import uz.hamkorbank.commhub.domain.model.type.MessageStatus;
import uz.hamkorbank.commhub.domain.model.type.RejectionReason;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;

/** The three message listeners of §8.1 (IK-01, TC-01, IK-04). */
@ExtendWith(MockitoExtension.class)
class InboundMessageListenerTest {

    private static final String DOCUMENT = """
            {
              "streamId": "ibank-retail",
              "externalMessageId": "ibank-2026-000123",
              "trafficClass": "NOTIFICATION",
              "recipient": { "msisdn": "998901234567" },
              "content": { "sms": { "text": "Kod: 123456" } }
            }
            """;

    @Mock
    private SubmitMessage submitMessage;

    private InboundMessageListener listener;

    @BeforeEach
    void setUp() {
        listener = new InboundMessageListener(
                new InboundMessageCodec(new InboundJson(), new InboundPayloadMapperImpl()), submitMessage);
    }

    @Test
    @DisplayName("TC-01: the critical topic submits as CRITICAL_OTP whatever the document claims")
    void classifiesByTopicNotByPayload() {
        // Arrange
        when(submitMessage.submit(any()))
                .thenReturn(SubmitMessageResult.accepted(MessageId.newId(), MessageStatus.QUEUED));

        // Act
        listener.onCritical(DOCUMENT);

        // Assert
        ArgumentCaptor<SubmitMessageCommand> command = ArgumentCaptor.forClass(SubmitMessageCommand.class);
        verify(submitMessage).submit(command.capture());
        assertThat(command.getValue().delivery().trafficClass()).isEqualTo(TrafficClass.CRITICAL_OTP);
    }

    @Test
    @DisplayName("Each topic carries its own traffic class into the pipeline")
    void classifiesEachTopic() {
        // Arrange
        when(submitMessage.submit(any()))
                .thenReturn(SubmitMessageResult.accepted(MessageId.newId(), MessageStatus.QUEUED));

        // Act
        listener.onTransactional(DOCUMENT);
        listener.onNotification(DOCUMENT);

        // Assert
        ArgumentCaptor<SubmitMessageCommand> commands = ArgumentCaptor.forClass(SubmitMessageCommand.class);
        verify(submitMessage, org.mockito.Mockito.times(2)).submit(commands.capture());
        assertThat(commands.getAllValues())
                .map(command -> command.delivery().trafficClass())
                .containsExactly(TrafficClass.TRANSACTIONAL, TrafficClass.NOTIFICATION);
    }

    @Test
    @DisplayName("FR-1.4: a refusal is a recorded verdict, not a listener failure — no redelivery")
    void doesNotFailOnARejection() {
        // Arrange
        when(submitMessage.submit(any()))
                .thenReturn(SubmitMessageResult.rejected(MessageId.newId(), RejectionReason.SUPPRESSED, "on the list"));

        // Act + Assert
        assertThatCode(() -> listener.onNotification(DOCUMENT)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("IK-04: an unreadable record fails so the error handler can route it to parse-error")
    void failsOnAPoisonPill() {
        // Act + Assert
        assertThatThrownBy(() -> listener.onCritical("not json at all")).isInstanceOf(InboundContractException.class);
        verify(submitMessage, never()).submit(any());
    }
}
