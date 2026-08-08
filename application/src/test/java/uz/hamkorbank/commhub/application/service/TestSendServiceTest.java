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
import static uz.hamkorbank.commhub.application.ApplicationFixtures.smsProvider;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import uz.hamkorbank.commhub.application.dto.SubmitMessageResult;
import uz.hamkorbank.commhub.application.exception.NotFoundException;
import uz.hamkorbank.commhub.application.port.in.SubmitMessage;
import uz.hamkorbank.commhub.application.port.in.command.SendTestMessageCommand;
import uz.hamkorbank.commhub.application.port.in.command.SubmitMessageCommand;
import uz.hamkorbank.commhub.application.port.out.AuditEntry;
import uz.hamkorbank.commhub.application.port.out.AuditPort;
import uz.hamkorbank.commhub.application.port.out.ClockPort;
import uz.hamkorbank.commhub.application.port.out.ProviderConfigRepository;
import uz.hamkorbank.commhub.domain.model.Actor;
import uz.hamkorbank.commhub.domain.model.Provider;
import uz.hamkorbank.commhub.domain.model.content.SmsContent;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.ChannelSelectionMode;
import uz.hamkorbank.commhub.domain.model.type.MessageStatus;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;

/** FR-7.4: a test send is a real send with one flag and, optionally, one pinned provider. */
class TestSendServiceTest {

    private static final Actor OPERATOR = Actor.operator("a.karimov");

    private SubmitMessage submitMessage;
    private ProviderConfigRepository providerConfig;
    private AuditPort audit;
    private TestSendService service;

    @BeforeEach
    void setUp() {
        submitMessage = mock(SubmitMessage.class);
        providerConfig = mock(ProviderConfigRepository.class);
        audit = mock(AuditPort.class);
        ClockPort clock = mock(ClockPort.class);
        when(clock.now()).thenReturn(NOW);
        when(submitMessage.submit(any()))
                .thenReturn(SubmitMessageResult.accepted(MessageId.newId(), MessageStatus.QUEUED));
        service = new TestSendService(submitMessage, providerConfig, audit, clock);
    }

    @Test
    @DisplayName("the submission is marked TEST and pinned to the requested channel")
    void submitsAsTestSendOnTheRequestedChannel() {
        // Arrange
        SendTestMessageCommand command = command(null);

        // Act
        service.send(command);

        // Assert
        SubmitMessageCommand submitted = captureSubmission();
        assertThat(submitted.delivery().test()).isTrue();
        assertThat(submitted.channelPlan().mode()).isEqualTo(ChannelSelectionMode.EXPLICIT);
        assertThat(submitted.channelPlan().channels()).containsExactly(Channel.SMS);
        assertThat(submitted.contents().forChannel(Channel.SMS))
                .get()
                .isInstanceOf(SmsContent.class)
                .extracting(content -> ((SmsContent) content).text())
                .isEqualTo("configuration check");
    }

    @Test
    @DisplayName("FR-1.5: repeating a test is not a duplicate — every send gets its own dedup key")
    void givesEveryTestSendItsOwnDedupKey() {
        // Arrange + Act
        service.send(command(null));
        service.send(command(null));

        // Assert
        ArgumentCaptor<SubmitMessageCommand> captor = ArgumentCaptor.forClass(SubmitMessageCommand.class);
        verify(submitMessage, org.mockito.Mockito.times(2)).submit(captor.capture());
        assertThat(captor.getAllValues().get(0).delivery().dedupKey())
                .isNotEqualTo(captor.getAllValues().get(1).delivery().dedupKey());
        assertThat(captor.getAllValues().get(0).externalMessageId())
                .isNotEqualTo(captor.getAllValues().get(1).externalMessageId());
    }

    @Test
    @DisplayName("a named provider is pinned so the profile under test is the one that runs")
    void pinsTheNamedProvider() {
        // Arrange
        Provider playmobile = smsProvider("PLAYMOBILE");
        when(providerConfig.findProviderByCode(ProviderCode.of("PLAYMOBILE"))).thenReturn(Optional.of(playmobile));

        // Act
        service.send(command(ProviderCode.of("PLAYMOBILE")));

        // Assert
        assertThat(captureSubmission().delivery().pinnedProvider()).isEqualTo(playmobile.ref());
    }

    @Test
    @DisplayName("a provider that is not configured is a 404 rather than a send to whoever routing picks")
    void refusesAnUnknownProvider() {
        // Arrange
        when(providerConfig.findProviderByCode(any())).thenReturn(Optional.empty());

        // Act + Assert
        assertThatExceptionOfType(NotFoundException.class)
                .isThrownBy(() -> service.send(command(ProviderCode.of("UNKNOWN"))));
    }

    @Test
    @DisplayName("a provider of another channel is refused: the test would prove nothing about either")
    void refusesAProviderOfAnotherChannel() {
        // Arrange
        when(providerConfig.findProviderByCode(ProviderCode.of("PLAYMOBILE")))
                .thenReturn(Optional.of(smsProvider("PLAYMOBILE")));
        SendTestMessageCommand emailOnSmsProvider = new SendTestMessageCommand(
                OPERATOR,
                STREAM_ID,
                Channel.EMAIL,
                recipient(),
                ProviderCode.of("PLAYMOBILE"),
                "configuration check",
                null);

        // Act + Assert
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> service.send(emailOnSmsProvider));
    }

    @Test
    @DisplayName("FR-7.3: the operator who aimed the Bank's infrastructure at a live address is journalled")
    void journalsTheOperator() {
        // Arrange + Act
        service.send(command(null));

        // Assert
        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).write(entry.capture());
        assertThat(entry.getValue().actor()).isEqualTo(OPERATOR);
        assertThat(entry.getValue().action()).isEqualTo("message.test-send");
        assertThat(entry.getValue().entityId()).isEqualTo("SMS");
    }

    private SendTestMessageCommand command(ProviderCode provider) {
        return new SendTestMessageCommand(
                OPERATOR, STREAM_ID, Channel.SMS, recipient(), provider, "configuration check", null);
    }

    private SubmitMessageCommand captureSubmission() {
        ArgumentCaptor<SubmitMessageCommand> captor = ArgumentCaptor.forClass(SubmitMessageCommand.class);
        verify(submitMessage).submit(captor.capture());
        return captor.getValue();
    }
}
