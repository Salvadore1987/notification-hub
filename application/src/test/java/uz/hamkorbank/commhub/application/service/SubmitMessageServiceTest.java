package uz.hamkorbank.commhub.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.EXTERNAL_ID;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.NOW;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.STREAM_ID;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.recipient;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.routingConfiguration;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.smsContents;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.smsProvider;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.stream;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.submitCommand;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import uz.hamkorbank.commhub.application.dto.SubmitMessageResult;
import uz.hamkorbank.commhub.application.mapper.MessageMapper;
import uz.hamkorbank.commhub.application.mapper.MessageMapperImpl;
import uz.hamkorbank.commhub.application.policy.DeduplicationPolicy;
import uz.hamkorbank.commhub.application.policy.FrequencyCapPolicy;
import uz.hamkorbank.commhub.application.policy.PanPolicy;
import uz.hamkorbank.commhub.application.port.in.command.SubmitMessageCommand;
import uz.hamkorbank.commhub.application.port.out.ClockPort;
import uz.hamkorbank.commhub.application.port.out.CustomerPreferencePort;
import uz.hamkorbank.commhub.application.port.out.DedupRegistryPort;
import uz.hamkorbank.commhub.application.port.out.FrequencyCounterPort;
import uz.hamkorbank.commhub.application.port.out.KillSwitchPort;
import uz.hamkorbank.commhub.application.port.out.KillSwitchState;
import uz.hamkorbank.commhub.application.port.out.MessageRepository;
import uz.hamkorbank.commhub.application.port.out.MetricsPort;
import uz.hamkorbank.commhub.application.port.out.OutboxEvent;
import uz.hamkorbank.commhub.application.port.out.OutboxPort;
import uz.hamkorbank.commhub.application.port.out.ProviderConfigRepository;
import uz.hamkorbank.commhub.application.port.out.QuotaCounterPort;
import uz.hamkorbank.commhub.application.port.out.StreamRepository;
import uz.hamkorbank.commhub.application.port.out.SuppressionRepository;
import uz.hamkorbank.commhub.application.port.out.TemplateRepository;
import uz.hamkorbank.commhub.application.service.pipeline.DeduplicationService;
import uz.hamkorbank.commhub.application.service.pipeline.DeliveryFilters;
import uz.hamkorbank.commhub.application.service.pipeline.MessagePipeline;
import uz.hamkorbank.commhub.application.service.pipeline.MessageValidator;
import uz.hamkorbank.commhub.application.service.pipeline.PanDetector;
import uz.hamkorbank.commhub.application.service.pipeline.QuotaGuard;
import uz.hamkorbank.commhub.application.service.pipeline.TemplateApplier;
import uz.hamkorbank.commhub.application.service.support.MessageRouting;
import uz.hamkorbank.commhub.application.service.support.MessageStatusNotifier;
import uz.hamkorbank.commhub.application.service.support.RoutingRotation;
import uz.hamkorbank.commhub.application.service.support.SuppressionRegistrar;
import uz.hamkorbank.commhub.domain.model.Message;
import uz.hamkorbank.commhub.domain.model.QuietHours;
import uz.hamkorbank.commhub.domain.model.QuotaConfig;
import uz.hamkorbank.commhub.domain.model.Stream;
import uz.hamkorbank.commhub.domain.model.SuppressionEntry;
import uz.hamkorbank.commhub.domain.model.Template;
import uz.hamkorbank.commhub.domain.model.TemplateRef;
import uz.hamkorbank.commhub.domain.model.TemplateVersion;
import uz.hamkorbank.commhub.domain.model.content.MessageContents;
import uz.hamkorbank.commhub.domain.model.content.SmsContent;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.ContentLocale;
import uz.hamkorbank.commhub.domain.model.type.MessageStatus;
import uz.hamkorbank.commhub.domain.model.type.QuotaExhaustionBehavior;
import uz.hamkorbank.commhub.domain.model.type.RejectionReason;
import uz.hamkorbank.commhub.domain.model.type.SuppressionReason;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;
import uz.hamkorbank.commhub.domain.model.vo.AddressHash;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;
import uz.hamkorbank.commhub.domain.model.vo.SuppressionEntryId;
import uz.hamkorbank.commhub.domain.model.vo.TemplateCode;
import uz.hamkorbank.commhub.domain.model.vo.TemplateId;
import uz.hamkorbank.commhub.domain.model.vo.TemplateVersionId;
import uz.hamkorbank.commhub.domain.service.FallbackChain;
import uz.hamkorbank.commhub.domain.service.Router;
import uz.hamkorbank.commhub.domain.service.SegmentCalculator;

/** Acceptance of a single message: the whole head of the pipeline (FR-1.1, FR-1.4, FR-1.5). */
class SubmitMessageServiceTest {

    private static final TemplateCode TEMPLATE_CODE = TemplateCode.of("OTP_LOGIN");

    private ClockPort clock;
    private StreamRepository streams;
    private MessageRepository messages;
    private KillSwitchPort killSwitch;
    private DedupRegistryPort dedupRegistry;
    private TemplateRepository templates;
    private SuppressionRepository suppressions;
    private CustomerPreferencePort preferences;
    private FrequencyCounterPort frequencyCounters;
    private QuotaCounterPort quotaCounters;
    private ProviderConfigRepository providerConfig;
    private OutboxPort outbox;
    private MetricsPort metrics;

    private Stream stream;
    private SubmitMessageService service;

    @BeforeEach
    void setUp() {
        clock = mock(ClockPort.class);
        streams = mock(StreamRepository.class);
        messages = mock(MessageRepository.class);
        killSwitch = mock(KillSwitchPort.class);
        dedupRegistry = mock(DedupRegistryPort.class);
        templates = mock(TemplateRepository.class);
        suppressions = mock(SuppressionRepository.class);
        preferences = mock(CustomerPreferencePort.class);
        frequencyCounters = mock(FrequencyCounterPort.class);
        quotaCounters = mock(QuotaCounterPort.class);
        providerConfig = mock(ProviderConfigRepository.class);
        outbox = mock(OutboxPort.class);
        metrics = mock(MetricsPort.class);
        stream = stream();

        when(clock.now()).thenReturn(NOW);
        when(streams.findById(STREAM_ID)).thenReturn(Optional.of(stream));
        when(messages.save(any(Message.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(killSwitch.state()).thenReturn(KillSwitchState.inactive());
        when(dedupRegistry.findOriginal(any(), any())).thenReturn(Optional.empty());
        when(dedupRegistry.register(any(), any(), any(), any())).thenReturn(Optional.empty());
        when(suppressions.findActiveByAddress(any(), any(), any())).thenReturn(Optional.empty());
        when(suppressions.findActiveByClient(any(), any(), any())).thenReturn(Optional.empty());
        when(preferences.find(any())).thenReturn(Optional.empty());
        when(frequencyCounters.countSince(any(), any(), any())).thenReturn(0L);
        when(quotaCounters.usage(any(), any(), any())).thenReturn(QuotaConfig.Usage.none());
        when(providerConfig.routingConfiguration(STREAM_ID))
                .thenReturn(routingConfiguration(List.of(smsProvider("PLAYMOBILE"))));

        MessageMapper mapper = new MessageMapperImpl();
        MessagePipeline pipeline = new MessagePipeline(
                new DeduplicationService(dedupRegistry, DeduplicationPolicy.defaults()),
                new TemplateApplier(templates),
                new MessageValidator(new PanDetector(), PanPolicy.rejecting(), metrics),
                new DeliveryFilters(
                        suppressions, preferences, frequencyCounters, FrequencyCapPolicy.defaults(), metrics),
                new QuotaGuard(quotaCounters, metrics),
                new MessageRouting(
                        new Router(new FallbackChain()),
                        new SegmentCalculator(),
                        providerConfig,
                        new RoutingRotation()),
                new SuppressionRegistrar(suppressions, metrics));
        service = new SubmitMessageService(
                clock,
                streams,
                messages,
                killSwitch,
                pipeline,
                new MessageStatusNotifier(outbox, metrics, mapper),
                mapper);
    }

    @Test
    @DisplayName("FR-1.1: an accepted message is routed, queued, saved and announced through the outbox")
    void acceptsAndQueuesMessage() {
        // Arrange + Act
        SubmitMessageResult result = service.submit(submitCommand());

        // Assert
        assertThat(result.isAccepted()).isTrue();
        assertThat(result.status()).isEqualTo(MessageStatus.QUEUED);
        Message saved = savedMessage();
        assertThat(saved.selectedChannel()).contains(Channel.SMS);
        assertThat(saved.selectedProvider()).isPresent();
        assertThat(saved.segments()).isEqualTo(1);
        assertThat(saved.cost()).isPresent();
        verify(outbox).append(any(OutboxEvent.class));
        verify(dedupRegistry).register(any(), eq(saved.id()), eq(NOW), any());
    }

    @Test
    @DisplayName("FR-1.4: a submission of an unknown stream is refused before a message is created")
    void refusesUnknownStream() {
        // Arrange
        when(streams.findById(STREAM_ID)).thenReturn(Optional.empty());

        // Act
        SubmitMessageResult result = service.submit(submitCommand());

        // Assert
        assertThat(result.reasonOptional()).contains(RejectionReason.VALIDATION_FAILED);
        assertThat(result.messageIdOptional()).isEmpty();
        verify(messages, never()).save(any());
    }

    @Test
    @DisplayName("IR-01: a suspended stream is refused with STREAM_SUSPENDED")
    void refusesSuspendedStream() {
        // Arrange
        stream.suspend();

        // Act
        SubmitMessageResult result = service.submit(submitCommand());

        // Assert
        assertThat(result.reasonOptional()).contains(RejectionReason.STREAM_SUSPENDED);
    }

    @Test
    @DisplayName("FR-3.2: the kill switch stops non-OTP traffic and leaves OTP alone")
    void killSwitchStopsBulkButNotOtp() {
        // Arrange
        when(killSwitch.state()).thenReturn(KillSwitchState.activated(false, NOW, "operator", "incident"));

        // Act
        SubmitMessageResult stopped = service.submit(commandWith(TrafficClass.NOTIFICATION, smsContents()));
        SubmitMessageResult otp = service.submit(commandWith(TrafficClass.CRITICAL_OTP, smsContents()));

        // Assert
        assertThat(stopped.reasonOptional()).contains(RejectionReason.KILL_SWITCH);
        assertThat(otp.isAccepted()).isTrue();
    }

    @Test
    @DisplayName("FR-1.5: a repeated submission inside the window returns DUPLICATE with the original")
    void detectsDuplicateBeforeProcessing() {
        // Arrange
        MessageId original = MessageId.newId();
        when(dedupRegistry.findOriginal(any(), any())).thenReturn(Optional.of(original));

        // Act
        SubmitMessageResult result = service.submit(submitCommand());

        // Assert
        assertThat(result.status()).isEqualTo(MessageStatus.DUPLICATE);
        assertThat(result.duplicateOfOptional()).contains(original);
        verify(messages, never()).save(any());
    }

    @Test
    @DisplayName("FR-1.5: losing the race on the dedup key also yields DUPLICATE and no send")
    void detectsDuplicateOnClaim() {
        // Arrange
        MessageId original = MessageId.newId();
        when(dedupRegistry.register(any(), any(), any(), any())).thenReturn(Optional.of(original));

        // Act
        SubmitMessageResult result = service.submit(submitCommand());

        // Assert
        assertThat(result.status()).isEqualTo(MessageStatus.DUPLICATE);
        assertThat(result.duplicateOfOptional()).contains(original);
        verify(messages, never()).save(any());
    }

    @Test
    @DisplayName("FR-4.3: a template renders the content and the merge variables are substituted")
    void rendersPublishedTemplate() {
        // Arrange
        when(templates.findByCode(TEMPLATE_CODE)).thenReturn(Optional.of(publishedTemplate("Kod: {CODE}")));
        SubmitMessageCommand command = commandWithTemplate(Map.of("CODE", "123456"));

        // Act
        SubmitMessageResult result = service.submit(command);

        // Assert
        assertThat(result.isAccepted()).isTrue();
        SmsContent content = (SmsContent) savedMessage().contents().requireForChannel(Channel.SMS);
        assertThat(content.text()).isEqualTo("Kod: 123456");
    }

    @Test
    @DisplayName("FR-4.3: a missing merge variable rejects the message in strict mode")
    void rejectsMissingMergeVariable() {
        // Arrange
        when(templates.findByCode(TEMPLATE_CODE)).thenReturn(Optional.of(publishedTemplate("Kod: {CODE}")));

        // Act
        SubmitMessageResult result = service.submit(commandWithTemplate(Map.of()));

        // Assert
        assertThat(result.reasonOptional()).contains(RejectionReason.TEMPLATE_VARIABLE_MISSING);
    }

    @Test
    @DisplayName("FR-4.1: a template without a published version rejects the message")
    void rejectsUnpublishedTemplate() {
        // Arrange
        Template template = Template.create(TemplateId.newId(), TEMPLATE_CODE, Channel.SMS, "МСБ", "author");
        template.addVersion(TemplateVersion.draft(
                TemplateVersionId.newId(),
                template.id(),
                1,
                ContentLocale.RU,
                TemplateVersion.Body.ofText("Kod: {CODE}"),
                "author"));
        when(templates.findByCode(TEMPLATE_CODE)).thenReturn(Optional.of(template));

        // Act
        SubmitMessageResult result = service.submit(commandWithTemplate(Map.of("CODE", "1")));

        // Assert
        assertThat(result.reasonOptional()).contains(RejectionReason.TEMPLATE_NOT_PUBLISHED);
    }

    @Test
    @DisplayName("FR-5.1: a suppressed address is rejected and the message keeps its history")
    void rejectsSuppressedRecipient() {
        // Arrange
        when(suppressions.findActiveByAddress(any(AddressHash.class), eq(Channel.SMS), eq(NOW)))
                .thenReturn(Optional.of(SuppressionEntry.forAddress(
                        SuppressionEntryId.newId(),
                        Channel.SMS,
                        AddressHash.ofMsisdn(recipient().msisdn()),
                        SuppressionReason.OPT_OUT,
                        NOW,
                        "operator")));

        // Act
        SubmitMessageResult result = service.submit(submitCommand());

        // Assert
        assertThat(result.reasonOptional()).contains(RejectionReason.SUPPRESSED);
        assertThat(savedMessage().status()).isEqualTo(MessageStatus.REJECTED);
    }

    @Test
    @DisplayName("FR-5.3: quiet hours configured to defer keep the message ROUTED instead of queueing it")
    void defersBulkMessageInsideQuietHours() {
        // Arrange — 10:15 UTC is 15:15 in Asia/Tashkent, inside the window
        stream.updateQuietHours(new QuietHours(
                LocalTime.of(8, 0),
                LocalTime.of(20, 0),
                ZoneId.of("Asia/Tashkent"),
                uz.hamkorbank.commhub.domain.model.type.QuietHoursBehavior.DEFER));

        // Act
        SubmitMessageResult result = service.submit(commandWith(TrafficClass.NOTIFICATION, smsContents()));

        // Assert
        assertThat(result.status()).isEqualTo(MessageStatus.ROUTED);
        assertThat(savedMessage().status()).isEqualTo(MessageStatus.ROUTED);
    }

    @Test
    @DisplayName("FR-5.3: quiet hours configured to reject refuse the message with QUIET_HOURS")
    void rejectsBulkMessageInsideRejectingQuietHours() {
        // Arrange
        stream.updateQuietHours(QuietHours.rejecting(LocalTime.of(8, 0), LocalTime.of(20, 0)));

        // Act
        SubmitMessageResult result = service.submit(commandWith(TrafficClass.NOTIFICATION, smsContents()));

        // Assert
        assertThat(result.reasonOptional()).contains(RejectionReason.QUIET_HOURS);
    }

    @Test
    @DisplayName("FR-2.6: an exhausted stream quota blocks the send with QUOTA_EXCEEDED")
    void rejectsWhenQuotaIsExhausted() {
        // Arrange
        stream.updateQuota(QuotaConfig.ofCounts(0L, null, QuotaExhaustionBehavior.BLOCK_AND_ALERT));

        // Act
        SubmitMessageResult result = service.submit(submitCommand());

        // Assert
        assertThat(result.reasonOptional()).contains(RejectionReason.QUOTA_EXCEEDED);
        verify(metrics).quotaBreached(eq(STREAM_ID), eq(Channel.SMS), any());
    }

    @Test
    @DisplayName("FR-2.2: without a selectable provider the message is rejected with NO_ROUTE_AVAILABLE")
    void rejectsWhenNoProviderIsAvailable() {
        // Arrange
        when(providerConfig.routingConfiguration(STREAM_ID)).thenReturn(routingConfiguration(List.of()));

        // Act
        SubmitMessageResult result = service.submit(submitCommand());

        // Assert
        assertThat(result.reasonOptional()).contains(RejectionReason.NO_ROUTE_AVAILABLE);
    }

    @Test
    @DisplayName("SEC-05: content with a full card number is rejected with PAN_DETECTED")
    void rejectsCardNumberInContent() {
        // Arrange
        MessageContents contents = MessageContents.of(SmsContent.of("Karta 4111 1111 1111 1111 blokirovana"));

        // Act
        SubmitMessageResult result = service.submit(commandWith(TrafficClass.TRANSACTIONAL, contents));

        // Assert
        assertThat(result.reasonOptional()).contains(RejectionReason.PAN_DETECTED);
    }

    @Test
    @DisplayName("MP-06: the SMS segment count is computed and stored before routing")
    void appliesSegmentation() {
        // Arrange
        MessageContents contents = MessageContents.of(SmsContent.of("a".repeat(200)));

        // Act
        service.submit(commandWith(TrafficClass.TRANSACTIONAL, contents));

        // Assert
        assertThat(savedMessage().segments()).isEqualTo(2);
    }

    @Test
    @DisplayName("FR-1.3: an accepted submission refreshes the activity of its stream")
    void touchesStreamOnAcceptance() {
        // Arrange + Act
        service.submit(submitCommand());

        // Assert
        assertThat(stream.lastActivityAt()).contains(NOW);
        verify(streams).save(stream);
        verify(metrics).messageAccepted(STREAM_ID, TrafficClass.TRANSACTIONAL, Channel.SMS);
    }

    @Test
    @DisplayName("FR-5.4: the frequency cap only alerts in the MVP and lets the message through")
    void frequencyCapAlertsWithoutBlocking() {
        // Arrange
        when(frequencyCounters.countSince(any(), eq(Channel.SMS), any())).thenReturn(99L);

        // Act
        SubmitMessageResult result = service.submit(commandWith(TrafficClass.NOTIFICATION, smsContents()));

        // Assert
        assertThat(result.isAccepted()).isTrue();
        verify(metrics).frequencyCapExceeded(eq(Channel.SMS), eq(99L), anyLong());
    }

    private Message savedMessage() {
        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(messages).save(captor.capture());
        return captor.getValue();
    }

    private static SubmitMessageCommand commandWith(TrafficClass trafficClass, MessageContents contents) {
        return new SubmitMessageCommand(
                STREAM_ID,
                EXTERNAL_ID,
                null,
                recipient(),
                contents,
                null,
                null,
                new SubmitMessageCommand.Delivery(trafficClass, null, null, null, null, false));
    }

    private static SubmitMessageCommand commandWithTemplate(Map<String, String> variables) {
        return new SubmitMessageCommand(
                STREAM_ID,
                EXTERNAL_ID,
                null,
                recipient(),
                null,
                null,
                TemplateRef.of(TEMPLATE_CODE, ContentLocale.RU, variables),
                SubmitMessageCommand.Delivery.defaults());
    }

    private static Template publishedTemplate(String text) {
        Template template = Template.create(TemplateId.newId(), TEMPLATE_CODE, Channel.SMS, "МСБ", "author");
        TemplateVersion version = TemplateVersion.draft(
                TemplateVersionId.newId(),
                template.id(),
                1,
                ContentLocale.RU,
                TemplateVersion.Body.ofText(text),
                "author");
        version.submitForReview();
        version.publish("reviewer", NOW);
        template.addVersion(version);
        return template;
    }
}
