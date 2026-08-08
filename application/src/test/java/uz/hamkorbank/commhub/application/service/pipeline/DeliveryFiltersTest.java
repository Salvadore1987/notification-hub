package uz.hamkorbank.commhub.application.service.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.NOW;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.STREAM_ID;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.msisdn;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.smsMessage;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.stream;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.hamkorbank.commhub.application.policy.FrequencyCapPolicy;
import uz.hamkorbank.commhub.application.port.out.CustomerPreferencePort;
import uz.hamkorbank.commhub.application.port.out.CustomerPreferences;
import uz.hamkorbank.commhub.application.port.out.FrequencyCounterPort;
import uz.hamkorbank.commhub.application.port.out.MetricsPort;
import uz.hamkorbank.commhub.application.port.out.SuppressionRepository;
import uz.hamkorbank.commhub.domain.model.Message;
import uz.hamkorbank.commhub.domain.model.MessageEnvelope;
import uz.hamkorbank.commhub.domain.model.QuietHours;
import uz.hamkorbank.commhub.domain.model.Stream;
import uz.hamkorbank.commhub.domain.model.SuppressionEntry;
import uz.hamkorbank.commhub.domain.model.content.SmsContent;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.ContentLocale;
import uz.hamkorbank.commhub.domain.model.type.QuietHoursBehavior;
import uz.hamkorbank.commhub.domain.model.type.RejectionReason;
import uz.hamkorbank.commhub.domain.model.type.SuppressionReason;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;
import uz.hamkorbank.commhub.domain.model.vo.AddressHash;
import uz.hamkorbank.commhub.domain.model.vo.ClientId;
import uz.hamkorbank.commhub.domain.model.vo.ExternalMessageId;
import uz.hamkorbank.commhub.domain.model.vo.Recipient;
import uz.hamkorbank.commhub.domain.model.vo.SuppressionEntryId;

/** The compliance filters: suppression, consent, quiet hours and the frequency cap (FR-5.1…FR-5.4). */
class DeliveryFiltersTest {

    private static final ClientId CLIENT = ClientId.of("CL-42");

    /** 23:00 Asia/Tashkent — inside a 22:00-08:00 window. */
    private static final Instant NIGHT = Instant.parse("2026-08-08T18:00:00Z");

    private SuppressionRepository suppressions;
    private CustomerPreferencePort preferences;
    private FrequencyCounterPort counters;
    private MetricsPort metrics;

    @BeforeEach
    void setUp() {
        suppressions = mock(SuppressionRepository.class);
        preferences = mock(CustomerPreferencePort.class);
        counters = mock(FrequencyCounterPort.class);
        metrics = mock(MetricsPort.class);
        when(suppressions.findActiveByAddress(any(), any(), any())).thenReturn(Optional.empty());
        when(suppressions.findActiveByClient(any(), any(), any())).thenReturn(Optional.empty());
        when(preferences.find(any())).thenReturn(Optional.empty());
        when(counters.countSince(any(), any(), any())).thenReturn(0L);
    }

    @Test
    @DisplayName("FR-5.1: a suppressed address is rejected with SUPPRESSED")
    void rejectsSuppressedAddress() {
        // Arrange
        when(suppressions.findActiveByAddress(AddressHash.ofMsisdn(msisdn()), Channel.SMS, NOW))
                .thenReturn(Optional.of(SuppressionEntry.forAddress(
                        SuppressionEntryId.newId(),
                        Channel.SMS,
                        AddressHash.ofMsisdn(msisdn()),
                        SuppressionReason.HARD_BOUNCE,
                        NOW,
                        "system")));

        // Act
        FilterVerdict verdict = filters().apply(smsMessage(), Channel.SMS, stream(), null, NOW);

        // Assert
        assertThat(verdict.isRejected()).isTrue();
        assertThat(verdict.reason()).isEqualTo(RejectionReason.SUPPRESSED);
    }

    @Test
    @DisplayName("FR-5.2: a client who opted out receives no bulk traffic")
    void rejectsOptedOutClientForBulkTraffic() {
        // Arrange
        when(preferences.find(CLIENT)).thenReturn(Optional.of(preference(false, null)));

        // Act
        FilterVerdict verdict = filters().apply(bulkMessage(NOW), Channel.SMS, stream(), null, NOW);

        // Assert
        assertThat(verdict.isRejected()).isTrue();
        assertThat(verdict.reason()).isEqualTo(RejectionReason.OPT_OUT);
    }

    @Test
    @DisplayName("FR-5.2: consent is not asked about transactional traffic")
    void ignoresConsentForTransactionalTraffic() {
        // Arrange
        when(preferences.find(any())).thenReturn(Optional.of(preference(false, null)));

        // Act
        FilterVerdict verdict = filters().apply(smsMessage(), Channel.SMS, stream(), null, NOW);

        // Assert
        assertThat(verdict.isPassed()).isTrue();
    }

    @Test
    @DisplayName("FR-5.3: a deferring window holds the message until it closes")
    void defersInsideQuietHours() {
        // Arrange
        Stream stream = streamWithQuietHours(QuietHours.deferring(LocalTime.of(22, 0), LocalTime.of(8, 0)));

        // Act
        FilterVerdict verdict = filters().apply(bulkMessage(NIGHT), Channel.SMS, stream, null, NIGHT);

        // Assert
        assertThat(verdict.isDeferred()).isTrue();
        assertThat(verdict.deferUntilOptional().orElseThrow()).isEqualTo(Instant.parse("2026-08-09T03:00:00Z"));
    }

    @Test
    @DisplayName("FR-5.3: a rejecting window answers QUIET_HOURS")
    void rejectsInsideQuietHours() {
        // Arrange
        Stream stream = streamWithQuietHours(QuietHours.rejecting(LocalTime.of(22, 0), LocalTime.of(8, 0)));

        // Act
        FilterVerdict verdict = filters().apply(bulkMessage(NIGHT), Channel.SMS, stream, null, NIGHT);

        // Assert
        assertThat(verdict.isRejected()).isTrue();
        assertThat(verdict.reason()).isEqualTo(RejectionReason.QUIET_HOURS);
    }

    @Test
    @DisplayName("FR-5.3: OTP and transactional traffic is never held by quiet hours")
    void neverHoldsTransactionalTraffic() {
        // Arrange
        Stream stream = streamWithQuietHours(QuietHours.deferring(LocalTime.of(22, 0), LocalTime.of(8, 0)));

        // Act
        FilterVerdict verdict = filters().apply(smsMessage(), Channel.SMS, stream, null, NIGHT);

        // Assert
        assertThat(verdict.isPassed()).isTrue();
    }

    @Test
    @DisplayName("FR-5.3, FR-8.2: the client's own window wins over the stream's")
    void prefersClientQuietHours() {
        // Arrange: поток молчит ночью, у клиента окно тишины другое — и оно сейчас открыто.
        Stream stream = streamWithQuietHours(QuietHours.rejecting(LocalTime.of(22, 0), LocalTime.of(8, 0)));
        QuietHours personal = new QuietHours(
                LocalTime.of(12, 0), LocalTime.of(14, 0), ZoneId.of("Asia/Tashkent"), QuietHoursBehavior.REJECT);
        when(preferences.find(CLIENT)).thenReturn(Optional.of(preference(true, personal)));

        // Act
        FilterVerdict verdict = filters().apply(bulkMessage(NIGHT), Channel.SMS, stream, null, NIGHT);

        // Assert
        assertThat(verdict.isPassed()).isTrue();
    }

    @Test
    @DisplayName("FR-5.4: an exceeded cap alerts but lets the message through in the MVP mode")
    void alertsOnFrequencyCapWithoutBlocking() {
        // Arrange
        when(counters.countSince(any(), any(), any())).thenReturn(11L);

        // Act
        FilterVerdict verdict = filters().apply(bulkMessage(NOW), Channel.SMS, stream(), null, NOW);

        // Assert
        assertThat(verdict.isPassed()).isTrue();
        verify(metrics).frequencyCapExceeded(Channel.SMS, 11L, 10L);
    }

    @Test
    @DisplayName("FR-5.4: with blocking switched on the cap rejects with FREQUENCY_CAPPED")
    void rejectsOverCapWhenBlocking() {
        // Arrange
        when(counters.countSince(any(), any(), any())).thenReturn(11L);
        DeliveryFilters blocking = new DeliveryFilters(
                suppressions, preferences, counters, new FrequencyCapPolicy(10, Duration.ofHours(24), true), metrics);

        // Act
        FilterVerdict verdict = blocking.apply(bulkMessage(NOW), Channel.SMS, stream(), null, NOW);

        // Assert
        assertThat(verdict.isRejected()).isTrue();
        assertThat(verdict.reason()).isEqualTo(RejectionReason.FREQUENCY_CAPPED);
    }

    @Test
    @DisplayName("FR-5.4: a registered send is counted against the address hash, not the address")
    void registersSendAgainstHash() {
        // Arrange + Act
        filters().registerSend(Recipient.ofMsisdn(msisdn()), Channel.SMS, NOW);

        // Assert
        verify(counters).register(AddressHash.ofMsisdn(msisdn()), Channel.SMS, NOW);
    }

    private DeliveryFilters filters() {
        return new DeliveryFilters(suppressions, preferences, counters, FrequencyCapPolicy.defaults(), metrics);
    }

    private static Stream streamWithQuietHours(QuietHours window) {
        Stream stream = stream();
        stream.updateQuietHours(window);
        return stream;
    }

    private static CustomerPreferences preference(boolean marketingOptIn, QuietHours quietHours) {
        return new CustomerPreferences(CLIENT, List.of(), ContentLocale.RU, marketingOptIn, quietHours);
    }

    /** Bulk message to a client: the class quiet hours and the frequency cap apply to (§6.2). */
    private static Message bulkMessage(Instant acceptedAt) {
        return Message.acceptSingleChannel(
                MessageEnvelope.single(STREAM_ID, ExternalMessageId.of("bulk000001"), TrafficClass.NOTIFICATION),
                new Recipient(CLIENT, msisdn(), null, List.of()),
                SmsContent.of("Yangi xizmat", "HAMKORBANK"),
                acceptedAt);
    }
}
