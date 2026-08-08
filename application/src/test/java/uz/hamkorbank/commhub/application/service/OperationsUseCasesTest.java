package uz.hamkorbank.commhub.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.NOW;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.STREAM_ID;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.smsMessage;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.smsProvider;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.stream;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import uz.hamkorbank.commhub.application.dto.ExpireMessagesResult;
import uz.hamkorbank.commhub.application.dto.KillSwitchResult;
import uz.hamkorbank.commhub.application.dto.ResendDlqResult;
import uz.hamkorbank.commhub.application.dto.StreamControlResult;
import uz.hamkorbank.commhub.application.mapper.MessageMapperImpl;
import uz.hamkorbank.commhub.application.port.in.command.ExpireMessagesCommand;
import uz.hamkorbank.commhub.application.port.in.command.KillSwitchCommand;
import uz.hamkorbank.commhub.application.port.in.command.ResendDlqCommand;
import uz.hamkorbank.commhub.application.port.in.command.StreamActionCommand;
import uz.hamkorbank.commhub.application.port.out.AuditEntry;
import uz.hamkorbank.commhub.application.port.out.AuditPort;
import uz.hamkorbank.commhub.application.port.out.ClockPort;
import uz.hamkorbank.commhub.application.port.out.DlqRepository;
import uz.hamkorbank.commhub.application.port.out.KillSwitchPort;
import uz.hamkorbank.commhub.application.port.out.KillSwitchState;
import uz.hamkorbank.commhub.application.port.out.MessageRepository;
import uz.hamkorbank.commhub.application.port.out.MetricsPort;
import uz.hamkorbank.commhub.application.port.out.OutboxPort;
import uz.hamkorbank.commhub.application.port.out.StreamRepository;
import uz.hamkorbank.commhub.application.service.support.MessageStatusNotifier;
import uz.hamkorbank.commhub.domain.model.Actor;
import uz.hamkorbank.commhub.domain.model.ChannelPlan;
import uz.hamkorbank.commhub.domain.model.DlqEntry;
import uz.hamkorbank.commhub.domain.model.Message;
import uz.hamkorbank.commhub.domain.model.MessageEnvelope;
import uz.hamkorbank.commhub.domain.model.Stream;
import uz.hamkorbank.commhub.domain.model.Timing;
import uz.hamkorbank.commhub.domain.model.content.MessageContents;
import uz.hamkorbank.commhub.domain.model.content.SmsContent;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.MessageStatus;
import uz.hamkorbank.commhub.domain.model.type.RejectionReason;
import uz.hamkorbank.commhub.domain.model.type.StreamStatus;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;
import uz.hamkorbank.commhub.domain.model.vo.ExternalMessageId;
import uz.hamkorbank.commhub.domain.model.vo.Msisdn;
import uz.hamkorbank.commhub.domain.model.vo.Recipient;

/** Operator-facing use cases: DLQ retry, TTL sweep, stream control and the kill switch. */
class OperationsUseCasesTest {

    private ClockPort clock;
    private MessageRepository messages;
    private DlqRepository dlqEntries;
    private StreamRepository streams;
    private KillSwitchPort killSwitch;
    private AuditPort audit;
    private OutboxPort outbox;
    private MessageStatusNotifier notifier;

    @BeforeEach
    void setUp() {
        clock = mock(ClockPort.class);
        messages = mock(MessageRepository.class);
        dlqEntries = mock(DlqRepository.class);
        streams = mock(StreamRepository.class);
        killSwitch = mock(KillSwitchPort.class);
        audit = mock(AuditPort.class);
        outbox = mock(OutboxPort.class);
        when(clock.now()).thenReturn(NOW);
        when(messages.save(any(Message.class))).thenAnswer(invocation -> invocation.getArgument(0));
        notifier = new MessageStatusNotifier(outbox, mock(MetricsPort.class), new MessageMapperImpl());
    }

    @Test
    @DisplayName("FR-3.3: a DLQ retry requeues the message and marks the entry as used")
    void retriesDlqEntry() {
        // Arrange
        Message message = failedMessage();
        DlqEntry entry = DlqEntry.of(message.id(), RejectionReason.ATTEMPTS_EXHAUSTED, "timeout", NOW);
        when(dlqEntries.findByMessageId(message.id())).thenReturn(Optional.of(entry));
        when(messages.findById(message.id())).thenReturn(Optional.of(message));
        ResendDlqService service = new ResendDlqService(clock, dlqEntries, messages, notifier, audit);

        // Act
        ResendDlqResult result = service.resend(ResendDlqCommand.of(message.id(), Actor.operator("ivanov")));

        // Assert
        assertThat(result.requeuedCount()).isEqualTo(1);
        assertThat(message.status()).isEqualTo(MessageStatus.QUEUED);
        assertThat(entry.isRetryable()).isFalse();
        verify(audit).write(any(AuditEntry.class));
        verify(outbox).append(any());
    }

    @Test
    @DisplayName("FR-3.3: an entry that was already retried is skipped instead of retried twice")
    void skipsAlreadyRetriedEntry() {
        // Arrange
        Message message = failedMessage();
        DlqEntry entry = DlqEntry.of(message.id(), RejectionReason.ATTEMPTS_EXHAUSTED, "timeout", NOW);
        entry.retry("petrov", NOW);
        when(dlqEntries.findByMessageId(message.id())).thenReturn(Optional.of(entry));
        when(messages.findById(message.id())).thenReturn(Optional.of(message));
        ResendDlqService service = new ResendDlqService(clock, dlqEntries, messages, notifier, audit);

        // Act
        ResendDlqResult result = service.resend(ResendDlqCommand.of(message.id(), Actor.operator("ivanov")));

        // Assert
        assertThat(result.requeuedCount()).isZero();
        assertThat(result.skipped()).containsExactly(message.id());
        assertThat(message.status()).isEqualTo(MessageStatus.FAILED);
    }

    @Test
    @DisplayName("FR-3.4: the sweep expires messages whose TTL elapsed and reports whether more remain")
    void expiresMessagesWithElapsedTtl() {
        // Arrange
        Message expired = otpMessage(Duration.ofMinutes(5));
        when(messages.findExpired(any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of(expired));
        when(clock.now()).thenReturn(NOW.plus(Duration.ofMinutes(6)));
        ExpireMessagesService service = new ExpireMessagesService(clock, messages, notifier);

        // Act
        ExpireMessagesResult result = service.expire(new ExpireMessagesCommand(10));

        // Assert
        assertThat(result.expired()).isEqualTo(1);
        assertThat(result.more()).isFalse();
        assertThat(expired.status()).isEqualTo(MessageStatus.EXPIRED);
    }

    @Test
    @DisplayName("FR-3.4: a message whose TTL still holds is left alone by the sweep")
    void keepsMessageWithLiveTtl() {
        // Arrange
        Message alive = otpMessage(Duration.ofMinutes(30));
        when(messages.findExpired(any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of(alive));
        ExpireMessagesService service = new ExpireMessagesService(clock, messages, notifier);

        // Act
        ExpireMessagesResult result = service.expire(ExpireMessagesCommand.defaults());

        // Assert
        assertThat(result.expired()).isZero();
        assertThat(alive.status()).isEqualTo(MessageStatus.QUEUED);
    }

    @Test
    @DisplayName("FR-3.2: suspending and resuming a stream changes its status and is audited")
    void controlsStream() {
        // Arrange
        Stream stream = stream();
        when(streams.findById(STREAM_ID)).thenReturn(Optional.of(stream));
        when(streams.save(any(Stream.class))).thenAnswer(invocation -> invocation.getArgument(0));
        StreamControlService service = new StreamControlService(clock, streams, audit);
        StreamActionCommand command = StreamActionCommand.of(STREAM_ID, Actor.operator("ivanov"));

        // Act
        StreamControlResult suspended = service.suspend(command);
        StreamControlResult resumed = service.resume(command);

        // Assert
        assertThat(suspended.status()).isEqualTo(StreamStatus.SUSPENDED);
        assertThat(resumed.status()).isEqualTo(StreamStatus.ACTIVE);
        verify(audit, org.mockito.Mockito.times(2)).write(any(AuditEntry.class));
    }

    @Test
    @DisplayName("FR-3.2: the kill switch is stored with its scope and audited before and after")
    void flipsKillSwitch() {
        // Arrange
        when(killSwitch.state()).thenReturn(KillSwitchState.inactive());
        KillSwitchService service = new KillSwitchService(clock, killSwitch, audit);

        // Act
        KillSwitchResult result =
                service.apply(KillSwitchCommand.activate(Actor.operator("ivanov"), "provider incident"));

        // Assert
        assertThat(result.active()).isTrue();
        assertThat(result.includesCriticalOtp()).isFalse();
        ArgumentCaptor<KillSwitchState> captor = ArgumentCaptor.forClass(KillSwitchState.class);
        verify(killSwitch).update(captor.capture());
        assertThat(captor.getValue().stops(TrafficClass.NOTIFICATION)).isTrue();
        assertThat(captor.getValue().stops(TrafficClass.CRITICAL_OTP)).isFalse();
        verify(audit).write(any(AuditEntry.class));
    }

    private Message failedMessage() {
        Message message = smsMessage();
        message.markValidated(Actor.system(), NOW);
        message.markRouted(Channel.SMS, smsProvider("PLAYMOBILE").ref(), Actor.system(), NOW);
        message.markQueued(Actor.system(), NOW);
        message.markSending(Actor.system(), NOW);
        message.markFailed("provider timed out", Actor.system(), NOW);
        return message;
    }

    private Message otpMessage(Duration ttl) {
        Message message = Message.accept(
                MessageEnvelope.single(STREAM_ID, ExternalMessageId.of("otp-1"), TrafficClass.CRITICAL_OTP),
                Recipient.ofMsisdn(Msisdn.of("998901234567")),
                ChannelPlan.explicitChannel(Channel.SMS),
                MessageContents.of(SmsContent.of("Kod: 1234")),
                null,
                Timing.withTtl(ttl),
                NOW);
        message.markValidated(Actor.system(), NOW);
        message.markRouted(Channel.SMS, smsProvider("PLAYMOBILE").ref(), Actor.system(), NOW);
        message.markQueued(Actor.system(), NOW);
        return message;
    }
}
