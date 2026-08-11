package uz.hamkorbank.commhub.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static uz.hamkorbank.commhub.domain.DomainFixtures.NOW;
import static uz.hamkorbank.commhub.domain.DomainFixtures.STREAM_ID;

import java.time.Duration;
import java.time.LocalTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.hamkorbank.commhub.domain.exception.DomainValidationException;
import uz.hamkorbank.commhub.domain.model.type.BalancingStrategy;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.ConnectionStatus;
import uz.hamkorbank.commhub.domain.model.type.IntegrationType;
import uz.hamkorbank.commhub.domain.model.type.Priority;
import uz.hamkorbank.commhub.domain.model.type.QuotaExhaustionBehavior;
import uz.hamkorbank.commhub.domain.model.type.StreamStatus;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;
import uz.hamkorbank.commhub.domain.model.vo.AdapterType;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.model.vo.ProviderId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderRef;

/** Inbound stream: defaults, suspension and connection liveness (FR-1.3, FR-2.4, TC-02). */
class StreamTest {

    @Test
    @DisplayName("a registered stream is active with unlimited quotas")
    void registrationDefaults() {
        // Act
        Stream stream = Stream.register(STREAM_ID, "Mobile application", IntegrationType.KAFKA, Stream.Defaults.none());

        // Assert
        assertThat(stream.status()).isEqualTo(StreamStatus.ACTIVE);
        assertThat(stream.isAcceptingTraffic()).isTrue();
        assertThat(stream.quota().isUnlimited()).isTrue();
        assertThat(stream.integrationType()).isEqualTo(IntegrationType.KAFKA);
        assertThat(stream.name()).isEqualTo("Mobile application");
        assertThat(stream.quietHours()).isEmpty();
        assertThat(stream.lastActivityAt()).isEmpty();
    }

    @Test
    @DisplayName("TC-02: an explicit traffic class wins over the stream default")
    void explicitTrafficClassWins() {
        // Arrange
        Stream stream = Stream.register(
                STREAM_ID, "CRM", IntegrationType.REST, Stream.Defaults.of(Channel.SMS, TrafficClass.NOTIFICATION));

        // Act + Assert
        assertThat(stream.effectiveTrafficClass(TrafficClass.CRITICAL_OTP)).isEqualTo(TrafficClass.CRITICAL_OTP);
        assertThat(stream.effectiveTrafficClass(null)).isEqualTo(TrafficClass.NOTIFICATION);
        assertThat(stream.effectiveChannel(Channel.EMAIL)).contains(Channel.EMAIL);
        assertThat(stream.effectiveChannel(null)).contains(Channel.SMS);
    }

    @Test
    @DisplayName("without any default the traffic class falls back to bulk NOTIFICATION")
    void fallsBackToBulkTraffic() {
        // Arrange
        Stream stream = Stream.register(STREAM_ID, "Ad hoc", IntegrationType.REST, Stream.Defaults.none());

        // Act + Assert
        assertThat(stream.effectiveTrafficClass(null)).isEqualTo(TrafficClass.NOTIFICATION);
        assertThat(stream.effectiveChannel(null)).isEmpty();
        assertThat(stream.effectivePriority(null, TrafficClass.CRITICAL_OTP)).isEqualTo(Priority.REALTIME);
        assertThat(stream.effectivePriority(Priority.HIGH, TrafficClass.NOTIFICATION))
                .isEqualTo(Priority.HIGH);
    }

    @Test
    @DisplayName("a stream default priority is used when the submission carries none")
    void streamDefaultPriorityIsUsed() {
        // Arrange
        Stream stream = Stream.register(
                STREAM_ID,
                "Marketing",
                IntegrationType.KAFKA,
                new Stream.Defaults(Channel.SMS, null, TrafficClass.NOTIFICATION, Priority.LOW, null));

        // Act + Assert
        assertThat(stream.effectivePriority(null, TrafficClass.NOTIFICATION)).isEqualTo(Priority.LOW);
    }

    @Test
    @DisplayName("FR-3.2: a suspended stream stops accepting traffic")
    void suspensionStopsTraffic() {
        // Arrange
        Stream stream = Stream.register(STREAM_ID, "CRM", IntegrationType.REST, Stream.Defaults.none());

        // Act
        stream.suspend();

        // Assert
        assertThat(stream.status()).isEqualTo(StreamStatus.SUSPENDED);
        assertThat(stream.isAcceptingTraffic()).isFalse();

        stream.activate();
        assertThat(stream.isAcceptingTraffic()).isTrue();

        stream.disable();
        assertThat(stream.status()).isEqualTo(StreamStatus.DISABLED);
    }

    @Test
    @DisplayName("FR-1.3: the connection status follows the last observed activity")
    void connectionStatusFollowsActivity() {
        // Arrange
        Stream stream = Stream.register(STREAM_ID, "CRM", IntegrationType.KAFKA, Stream.Defaults.none());

        // Act + Assert
        assertThat(stream.connectionStatus(NOW)).isEqualTo(ConnectionStatus.UNKNOWN);

        stream.touch(NOW);
        assertThat(stream.connectionStatus(NOW.plus(Duration.ofMinutes(1)))).isEqualTo(ConnectionStatus.CONNECTED);
        assertThat(stream.connectionStatus(NOW.plus(Duration.ofMinutes(20)))).isEqualTo(ConnectionStatus.IDLE);
        assertThat(stream.connectionStatus(NOW.plus(Duration.ofDays(2)))).isEqualTo(ConnectionStatus.DISCONNECTED);
        assertThat(stream.lastActivityAt()).contains(NOW);

        stream.touch(NOW.minusSeconds(60));
        assertThat(stream.lastActivityAt()).contains(NOW);
    }

    @Test
    @DisplayName("quotas, quiet hours, credentials and defaults are editable at runtime (AD-07)")
    void configurationIsEditable() {
        // Arrange
        Stream stream = Stream.register(STREAM_ID, "CRM", IntegrationType.KAFKA, Stream.Defaults.none());

        // Act
        stream.updateQuota(QuotaConfig.ofCounts(1_000L, 10_000L, QuotaExhaustionBehavior.BLOCK_AND_ALERT));
        stream.updateQuietHours(QuietHours.deferring(LocalTime.of(22, 0), LocalTime.of(8, 0)));
        stream.updateDefaults(Stream.Defaults.of(Channel.EMAIL, TrafficClass.TRANSACTIONAL));

        // Assert
        assertThat(stream.quota().dailyCountLimit()).contains(1_000L);
        assertThat(stream.quietHours()).isPresent();
        assertThat(stream.defaults().channelOptional()).contains(Channel.EMAIL);
    }

    @Test
    @DisplayName("a default provider must serve the default channel")
    void defaultsAreConsistent() {
        // Arrange
        ProviderRef pushProvider = new ProviderRef(
                ProviderId.newId(), ProviderCode.of("FCM"), Channel.PUSH, AdapterType.of("fcm-http-v1"));

        // Act + Assert
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> new Stream.Defaults(Channel.SMS, pushProvider, null, null, null))
                .withMessageContaining("does not serve default channel");
        assertThat(new Stream.Defaults(Channel.PUSH, pushProvider, null, null, null).providerOptional())
                .contains(pushProvider);
    }

    @Test
    @DisplayName("a stream needs a name")
    void nameIsRequired() {
        // Act + Assert
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> Stream.register(STREAM_ID, " ", IntegrationType.KAFKA, Stream.Defaults.none()));
    }

    @Test
    @DisplayName("IR-02, FR-2.3: a stream carries its own request limit and balancing strategy")
    void streamLimitAndStrategyAreConfigurable() {
        // Arrange
        Stream stream = Stream.register(STREAM_ID, "iBank", IntegrationType.REST, Stream.Defaults.none());

        // Act
        stream.updateRateLimit(new RateLimit(500, 20_000, 0));
        stream.updateDefaults(Stream.Defaults.none().withBalancingStrategy(BalancingStrategy.LEAST_COST));

        // Assert
        assertThat(stream.rateLimit().tps()).isEqualTo(500);
        assertThat(stream.rateLimit().perMinute()).isEqualTo(20_000);
        assertThat(stream.defaults().balancingStrategyOptional()).contains(BalancingStrategy.LEAST_COST);
        assertThat(Stream.register(STREAM_ID, "CRM", IntegrationType.KAFKA, Stream.Defaults.none())
                        .rateLimit()
                        .isUnlimited())
                .isTrue();
        assertThatExceptionOfType(DomainValidationException.class).isThrownBy(() -> stream.updateRateLimit(null));
    }
}
