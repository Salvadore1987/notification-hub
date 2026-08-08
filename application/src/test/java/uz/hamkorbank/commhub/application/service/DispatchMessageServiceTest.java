package uz.hamkorbank.commhub.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.NOW;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.STREAM_ID;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.routingConfiguration;
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
import uz.hamkorbank.commhub.application.dto.DispatchResult;
import uz.hamkorbank.commhub.application.dto.DispatchResult.DispatchOutcome;
import uz.hamkorbank.commhub.application.mapper.MessageMapperImpl;
import uz.hamkorbank.commhub.application.policy.DeduplicationPolicy;
import uz.hamkorbank.commhub.application.policy.EmailPolicy;
import uz.hamkorbank.commhub.application.policy.FrequencyCapPolicy;
import uz.hamkorbank.commhub.application.policy.PanPolicy;
import uz.hamkorbank.commhub.application.policy.SendingPolicy;
import uz.hamkorbank.commhub.application.port.in.command.DispatchMessageCommand;
import uz.hamkorbank.commhub.application.port.out.BatchRepository;
import uz.hamkorbank.commhub.application.port.out.ClockPort;
import uz.hamkorbank.commhub.application.port.out.CustomerPreferencePort;
import uz.hamkorbank.commhub.application.port.out.DedupRegistryPort;
import uz.hamkorbank.commhub.application.port.out.DlqRepository;
import uz.hamkorbank.commhub.application.port.out.FrequencyCounterPort;
import uz.hamkorbank.commhub.application.port.out.KillSwitchPort;
import uz.hamkorbank.commhub.application.port.out.KillSwitchState;
import uz.hamkorbank.commhub.application.port.out.MessageRepository;
import uz.hamkorbank.commhub.application.port.out.MetricsPort;
import uz.hamkorbank.commhub.application.port.out.OutboxPort;
import uz.hamkorbank.commhub.application.port.out.ProviderConfigRepository;
import uz.hamkorbank.commhub.application.port.out.QuotaCounterPort;
import uz.hamkorbank.commhub.application.port.out.StreamRepository;
import uz.hamkorbank.commhub.application.port.out.SuppressionRepository;
import uz.hamkorbank.commhub.application.port.out.TemplateRepository;
import uz.hamkorbank.commhub.application.port.out.provider.ProviderAck;
import uz.hamkorbank.commhub.application.service.pipeline.DeduplicationService;
import uz.hamkorbank.commhub.application.service.pipeline.DeliveryFilters;
import uz.hamkorbank.commhub.application.service.pipeline.MessagePipeline;
import uz.hamkorbank.commhub.application.service.pipeline.MessageValidator;
import uz.hamkorbank.commhub.application.service.pipeline.PanDetector;
import uz.hamkorbank.commhub.application.service.pipeline.QuotaGuard;
import uz.hamkorbank.commhub.application.service.pipeline.TemplateApplier;
import uz.hamkorbank.commhub.application.service.support.DispatchGuards;
import uz.hamkorbank.commhub.application.service.support.MessageRouting;
import uz.hamkorbank.commhub.application.service.support.MessageStatusNotifier;
import uz.hamkorbank.commhub.application.service.support.ProviderGateway;
import uz.hamkorbank.commhub.application.service.support.RoutingRotation;
import uz.hamkorbank.commhub.application.service.support.SuppressionRegistrar;
import uz.hamkorbank.commhub.domain.model.Actor;
import uz.hamkorbank.commhub.domain.model.Batch;
import uz.hamkorbank.commhub.domain.model.DlqEntry;
import uz.hamkorbank.commhub.domain.model.Message;
import uz.hamkorbank.commhub.domain.model.MessageEnvelope;
import uz.hamkorbank.commhub.domain.model.Provider;
import uz.hamkorbank.commhub.domain.model.SuppressionEntry;
import uz.hamkorbank.commhub.domain.model.Timing;
import uz.hamkorbank.commhub.domain.model.content.SmsContent;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.ErrorClass;
import uz.hamkorbank.commhub.domain.model.type.MessageStatus;
import uz.hamkorbank.commhub.domain.model.type.SuppressionReason;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;
import uz.hamkorbank.commhub.domain.model.vo.BatchId;
import uz.hamkorbank.commhub.domain.model.vo.ExternalMessageId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderMessageId;
import uz.hamkorbank.commhub.domain.model.vo.Recipient;
import uz.hamkorbank.commhub.domain.service.FallbackChain;
import uz.hamkorbank.commhub.domain.service.Router;
import uz.hamkorbank.commhub.domain.service.SegmentCalculator;

/** The sending saga: attempts, retries, failover, DLQ and the guards (AD-04, PR-01, FR-3.2…FR-3.4). */
class DispatchMessageServiceTest {

    private ClockPort clock;
    private MessageRepository messages;
    private DlqRepository dlqEntries;
    private ProviderGateway gateway;
    private KillSwitchPort killSwitch;
    private BatchRepository batches;
    private StreamRepository streams;
    private ProviderConfigRepository providerConfig;
    private OutboxPort outbox;
    private SuppressionRepository suppressions;

    private Provider playmobile;
    private Provider smsgate;
    private DispatchMessageService service;

    @BeforeEach
    void setUp() {
        clock = mock(ClockPort.class);
        messages = mock(MessageRepository.class);
        dlqEntries = mock(DlqRepository.class);
        gateway = mock(ProviderGateway.class);
        killSwitch = mock(KillSwitchPort.class);
        batches = mock(BatchRepository.class);
        streams = mock(StreamRepository.class);
        providerConfig = mock(ProviderConfigRepository.class);
        outbox = mock(OutboxPort.class);
        playmobile = smsProvider("PLAYMOBILE");
        smsgate = smsProvider("SMSGATE");

        when(clock.now()).thenReturn(NOW);
        when(messages.save(any(Message.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(killSwitch.state()).thenReturn(KillSwitchState.inactive());
        when(batches.findById(any())).thenReturn(Optional.empty());
        when(streams.findById(STREAM_ID)).thenReturn(Optional.of(stream()));
        when(providerConfig.routingConfiguration(STREAM_ID))
                .thenReturn(routingConfiguration(List.of(playmobile, smsgate)));
        when(gateway.providerMessageIdFor(any())).thenReturn(ProviderMessageId.of("HB0000000001"));

        suppressions = mock(SuppressionRepository.class);
        when(suppressions.saveIfAbsent(any())).thenAnswer(invocation -> invocation.getArgument(0));
        MessagePipeline pipeline = new MessagePipeline(
                new DeduplicationService(mock(DedupRegistryPort.class), DeduplicationPolicy.defaults()),
                new TemplateApplier(mock(TemplateRepository.class)),
                new MessageValidator(
                        new PanDetector(), PanPolicy.rejecting(), EmailPolicy.defaults(), mock(MetricsPort.class)),
                new DeliveryFilters(
                        suppressions,
                        mock(CustomerPreferencePort.class),
                        mock(FrequencyCounterPort.class),
                        FrequencyCapPolicy.defaults(),
                        mock(MetricsPort.class)),
                new QuotaGuard(mock(QuotaCounterPort.class), mock(MetricsPort.class)),
                new MessageRouting(
                        new Router(new FallbackChain()),
                        new SegmentCalculator(),
                        providerConfig,
                        new RoutingRotation()),
                new SuppressionRegistrar(suppressions, mock(MetricsPort.class)));
        service = new DispatchMessageService(
                clock,
                messages,
                dlqEntries,
                gateway,
                new DispatchGuards(killSwitch, batches, streams),
                pipeline,
                new MessageStatusNotifier(outbox, mock(MetricsPort.class), new MessageMapperImpl()),
                SendingPolicy.defaults());
    }

    @Test
    @DisplayName("AD-04: an accepted submission moves the message to SENT_TO_PROVIDER")
    void handsMessageToProvider() {
        // Arrange
        Message message = queuedMessage();
        when(gateway.submit(any(), any(), any(), any()))
                .thenReturn(ProviderAck.accepted(ProviderMessageId.of("PM-1"), "0", NOW));

        // Act
        DispatchResult result = service.dispatch(DispatchMessageCommand.of(message.id()));

        // Assert
        assertThat(result.outcome()).isEqualTo(DispatchOutcome.SENT);
        assertThat(message.status()).isEqualTo(MessageStatus.SENT_TO_PROVIDER);
        assertThat(message.latestAttempt().orElseThrow().isAccepted()).isTrue();
        verify(outbox).append(any());
    }

    @Test
    @DisplayName("PR-01: a retryable failure schedules another attempt on the same provider")
    void retriesRetryableFailure() {
        // Arrange
        Message message = queuedMessage();
        when(gateway.submit(any(), any(), any(), any())).thenReturn(ProviderAck.timedOut(NOW));

        // Act
        DispatchResult result = service.dispatch(DispatchMessageCommand.of(message.id()));

        // Assert
        assertThat(result.outcome()).isEqualTo(DispatchOutcome.RETRY_SCHEDULED);
        assertThat(message.status()).isEqualTo(MessageStatus.RETRYING);
        assertThat(message.selectedProvider()).contains(playmobile.ref());
    }

    @Test
    @DisplayName("§18.1: a blocking provider error fails over to the next provider of the channel")
    void failsOverOnBlockingError() {
        // Arrange — Playmobile 102 Account lock is provider-wide
        Message message = queuedMessage();
        when(gateway.submit(any(), any(), any(), any()))
                .thenReturn(ProviderAck.failed("102", ErrorClass.BLOCKING, "Account lock", NOW));

        // Act
        DispatchResult result = service.dispatch(DispatchMessageCommand.of(message.id()));

        // Assert
        assertThat(result.outcome()).isEqualTo(DispatchOutcome.RETRY_SCHEDULED);
        assertThat(message.selectedProvider()).contains(smsgate.ref());
    }

    @Test
    @DisplayName("§18.1: a permanent rejection ends the message as UNDELIVERED without a fallback")
    void endsPermanentRejection() {
        // Arrange
        Message message = queuedMessage();
        when(gateway.submit(any(), any(), any(), any()))
                .thenReturn(ProviderAck.rejected("401", "Invalid recipient", NOW));

        // Act
        DispatchResult result = service.dispatch(DispatchMessageCommand.of(message.id()));

        // Assert
        assertThat(result.outcome()).isEqualTo(DispatchOutcome.UNDELIVERED);
        assertThat(message.status()).isEqualTo(MessageStatus.UNDELIVERED);
        verify(dlqEntries, never()).save(any());
    }

    @Test
    @DisplayName("§18.2 code 20, FR-5.1: an address the provider calls unusable goes on the suppression list")
    void suppressesAddressRejectedByProvider() {
        // Arrange — SMS Gate 20: номер в чёрном списке, адрес больше не годится
        Message message = queuedMessage();
        when(gateway.submit(any(), any(), any(), any()))
                .thenReturn(
                        ProviderAck.rejected("20", "Number in blacklist", NOW).withInvalidRecipient());

        // Act
        DispatchResult result = service.dispatch(DispatchMessageCommand.of(message.id()));

        // Assert
        assertThat(result.outcome()).isEqualTo(DispatchOutcome.UNDELIVERED);
        ArgumentCaptor<SuppressionEntry> entry = ArgumentCaptor.forClass(SuppressionEntry.class);
        verify(suppressions).saveIfAbsent(entry.capture());
        assertThat(entry.getValue().reason()).isEqualTo(SuppressionReason.PROVIDER_BLACKLIST);
        assertThat(entry.getValue().channel()).contains(Channel.SMS);
    }

    @Test
    @DisplayName("PR-01: an ordinary failure leaves the address alone")
    void leavesAddressAloneOnOrdinaryFailure() {
        // Arrange
        Message message = queuedMessage();
        when(gateway.submit(any(), any(), any(), any())).thenReturn(ProviderAck.timedOut(NOW));

        // Act
        service.dispatch(DispatchMessageCommand.of(message.id()));

        // Assert
        verify(suppressions, never()).saveIfAbsent(any());
    }

    @Test
    @DisplayName("FR-3.3: an exhausted attempt budget fails the message and writes a DLQ entry")
    void movesToDlqWhenBudgetIsExhausted() {
        // Arrange — the provider keeps timing out, so retries and the failover are used up
        Message message = queuedMessage();
        when(gateway.submit(any(), any(), any(), any())).thenReturn(ProviderAck.timedOut(NOW));

        // Act — dispatch until the saga stops asking for another turn
        DispatchResult result = service.dispatch(DispatchMessageCommand.of(message.id()));
        while (result.needsAnotherTurn()) {
            result = service.dispatch(DispatchMessageCommand.of(message.id()));
        }

        // Assert
        assertThat(result.outcome()).isEqualTo(DispatchOutcome.FAILED);
        assertThat(message.status()).isEqualTo(MessageStatus.FAILED);
        assertThat(message.attempts())
                .hasSizeLessThanOrEqualTo(SendingPolicy.defaults().maxTotalAttempts());
        assertThat(message.attempts().stream()
                        .map(attempt -> attempt.provider().code().value())
                        .distinct())
                .containsExactly("PLAYMOBILE", "SMSGATE");
        verify(dlqEntries).save(any(DlqEntry.class));
    }

    @Test
    @DisplayName("FR-3.4: a message whose TTL elapsed expires instead of being sent")
    void expiresMessageWithElapsedTtl() {
        // Arrange
        Message message = messageWith(Timing.withTtl(Duration.ofMinutes(5)));
        message.markValidated(Actor.system(), NOW);
        message.markRouted(Channel.SMS, playmobile.ref(), Actor.system(), NOW);
        message.markQueued(Actor.system(), NOW);
        when(messages.findById(message.id())).thenReturn(Optional.of(message));
        when(clock.now()).thenReturn(NOW.plus(Duration.ofMinutes(6)));

        // Act
        DispatchResult result = service.dispatch(DispatchMessageCommand.of(message.id()));

        // Assert
        assertThat(result.outcome()).isEqualTo(DispatchOutcome.EXPIRED);
        assertThat(message.status()).isEqualTo(MessageStatus.EXPIRED);
        verify(gateway, never()).submit(any(), any(), any(), any());
    }

    @Test
    @DisplayName("FR-3.2: a stopped batch cancels its remaining messages when they come up")
    void cancelsMessagesOfStoppedBatch() {
        // Arrange
        BatchId batchId = BatchId.newId();
        Message message = batchedMessage(batchId);
        Batch batch = Batch.accept(batchId, STREAM_ID, Channel.SMS, 10L, Timing.immediate(), NOW);
        batch.stop();
        when(batches.findById(batchId)).thenReturn(Optional.of(batch));

        // Act
        DispatchResult result = service.dispatch(DispatchMessageCommand.of(message.id()));

        // Assert
        assertThat(result.outcome()).isEqualTo(DispatchOutcome.CANCELLED);
        assertThat(message.status()).isEqualTo(MessageStatus.CANCELLED);
        verify(gateway, never()).submit(any(), any(), any(), any());
    }

    @Test
    @DisplayName("FR-3.2: the kill switch defers a message instead of cancelling it")
    void defersWhileKillSwitchIsActive() {
        // Arrange
        Message message = queuedMessage();
        when(killSwitch.state()).thenReturn(KillSwitchState.activated(false, NOW, "operator", "incident"));

        // Act
        DispatchResult result = service.dispatch(DispatchMessageCommand.of(message.id()));

        // Assert
        assertThat(result.outcome()).isEqualTo(DispatchOutcome.DEFERRED);
        assertThat(message.status()).isEqualTo(MessageStatus.QUEUED);
        verify(gateway, never()).submit(any(), any(), any(), any());
    }

    @Test
    @DisplayName("ST-02: a message that already reached a terminal status is skipped")
    void skipsTerminalMessage() {
        // Arrange
        Message message = queuedMessage();
        message.cancel(uz.hamkorbank.commhub.domain.model.type.RejectionReason.SEND_STOPPED, Actor.system(), NOW);

        // Act
        DispatchResult result = service.dispatch(DispatchMessageCommand.of(message.id()));

        // Assert
        assertThat(result.outcome()).isEqualTo(DispatchOutcome.SKIPPED);
        verify(gateway, never()).submit(any(), any(), any(), any());
    }

    /** Message routed onto Playmobile and waiting for its turn. */
    private Message queuedMessage() {
        Message message = smsMessage();
        message.markValidated(Actor.system(), NOW);
        message.markRouted(Channel.SMS, playmobile.ref(), Actor.system(), NOW);
        message.markQueued(Actor.system(), NOW);
        when(messages.findById(message.id())).thenReturn(Optional.of(message));
        return message;
    }

    private Message batchedMessage(BatchId batchId) {
        Message message = Message.acceptSingleChannel(
                MessageEnvelope.batched(
                        STREAM_ID, ExternalMessageId.of("batch-item-1"), TrafficClass.NOTIFICATION, batchId),
                Recipient.ofMsisdn(uz.hamkorbank.commhub.domain.model.vo.Msisdn.of("998901234567")),
                SmsContent.of("Hello"),
                NOW);
        message.markValidated(Actor.system(), NOW);
        message.markRouted(Channel.SMS, playmobile.ref(), Actor.system(), NOW);
        message.markQueued(Actor.system(), NOW);
        when(messages.findById(message.id())).thenReturn(Optional.of(message));
        return message;
    }

    private Message messageWith(Timing timing) {
        return Message.accept(
                MessageEnvelope.single(STREAM_ID, ExternalMessageId.of("otp-1"), TrafficClass.CRITICAL_OTP),
                Recipient.ofMsisdn(uz.hamkorbank.commhub.domain.model.vo.Msisdn.of("998901234567")),
                uz.hamkorbank.commhub.domain.model.ChannelPlan.explicitChannel(Channel.SMS),
                uz.hamkorbank.commhub.domain.model.content.MessageContents.of(SmsContent.of("Kod: 1234")),
                null,
                timing,
                NOW);
    }
}
