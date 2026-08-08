package uz.hamkorbank.commhub.adapter.out.persistence.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import uz.hamkorbank.commhub.adapter.out.persistence.AbstractPersistenceIT;
import uz.hamkorbank.commhub.domain.model.ChannelConfig;
import uz.hamkorbank.commhub.domain.model.Provider;
import uz.hamkorbank.commhub.domain.model.QuietHours;
import uz.hamkorbank.commhub.domain.model.QuotaConfig;
import uz.hamkorbank.commhub.domain.model.RateLimit;
import uz.hamkorbank.commhub.domain.model.RoutingPolicy;
import uz.hamkorbank.commhub.domain.model.Stream;
import uz.hamkorbank.commhub.domain.model.Tariff;
import uz.hamkorbank.commhub.domain.model.type.BalancingStrategy;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.ChannelStatus;
import uz.hamkorbank.commhub.domain.model.type.IntegrationType;
import uz.hamkorbank.commhub.domain.model.type.Priority;
import uz.hamkorbank.commhub.domain.model.type.ProviderHealthStatus;
import uz.hamkorbank.commhub.domain.model.type.QuotaExhaustionBehavior;
import uz.hamkorbank.commhub.domain.model.type.StreamStatus;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;
import uz.hamkorbank.commhub.domain.model.vo.AdapterType;
import uz.hamkorbank.commhub.domain.model.vo.Money;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.model.vo.ProviderId;
import uz.hamkorbank.commhub.domain.model.vo.RoutingPolicyId;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;
import uz.hamkorbank.commhub.domain.service.RoutingConfiguration;

/** Routing configuration must survive a restart exactly as the admin panel left it (FR-2.1…FR-2.4, AD-07). */
class ConfigPersistenceIT extends AbstractPersistenceIT {

    private static final Currency UZS = Currency.getInstance("UZS");

    private final ProviderConfigPersistenceAdapter providers;
    private final StreamPersistenceAdapter streams;

    ConfigPersistenceIT(
            JdbcClient jdbcClient,
            TransactionTemplate transactionTemplate,
            ProviderConfigPersistenceAdapter providers,
            StreamPersistenceAdapter streams) {
        super(jdbcClient, transactionTemplate);
        this.providers = providers;
        this.streams = streams;
    }

    @BeforeEach
    void clearConfiguration() {
        truncate("routing_policy", "channel", "stream", "provider");
    }

    @Test
    @DisplayName("a provider keeps its tariff, limits and availability (FR-2.1, FR-2.7)")
    void providerRoundTrips() {
        // Arrange
        Provider provider = playmobile();
        provider.enterMaintenance();
        provider.markHealth(ProviderHealthStatus.DEGRADED);

        // Act
        providers.save(provider);
        Optional<Provider> restored = providers.findProviderByCode(ProviderCode.of("PLAYMOBILE"));

        // Assert
        assertThat(restored).isPresent();
        Provider stored = restored.orElseThrow();
        assertThat(stored.id()).isEqualTo(provider.id());
        assertThat(stored.adapterType()).isEqualTo(AdapterType.of("playmobile-http"));
        assertThat(stored.weight()).isEqualTo(30);
        assertThat(stored.tariff().orElseThrow().perSegment().amount()).isEqualByComparingTo("120.5000");
        assertThat(stored.rateLimit()).isEqualTo(new RateLimit(50, 1_000, 5));
        assertThat(stored.isInMaintenance()).isTrue();
        assertThat(stored.health()).isEqualTo(ProviderHealthStatus.DEGRADED);
        assertThat(stored.isSelectable()).isFalse();
    }

    @Test
    @DisplayName("a channel keeps its fallback order as provider references (FR-2.2)")
    void channelKeepsFallbackOrder() {
        // Arrange
        Provider primary = providers.save(playmobile());
        Provider backup = providers.save(smsGate());
        ChannelConfig channel = ChannelConfig.of(Channel.SMS, BalancingStrategy.WEIGHTED);
        channel.updateFallbackOrder(List.of(primary.ref(), backup.ref()));
        channel.updateQuietHours(QuietHours.deferring(LocalTime.of(21, 0), LocalTime.of(8, 0)));
        channel.enterMaintenance();

        // Act
        providers.save(channel);
        ChannelConfig restored = providers.findChannel(Channel.SMS).orElseThrow();

        // Assert
        assertThat(restored.status()).isEqualTo(ChannelStatus.MAINTENANCE);
        assertThat(restored.balancingStrategy()).isEqualTo(BalancingStrategy.WEIGHTED);
        assertThat(restored.fallbackOrder()).containsExactly(primary.ref(), backup.ref());
        assertThat(restored.quietHours()).contains(QuietHours.deferring(LocalTime.of(21, 0), LocalTime.of(8, 0)));
    }

    @Test
    @DisplayName("a fallback entry whose provider is gone is dropped, the channel still loads (FR-2.2)")
    void danglingFallbackEntryIsIgnored() {
        // Arrange
        Provider primary = providers.save(playmobile());
        ChannelConfig channel = ChannelConfig.of(Channel.SMS, BalancingStrategy.PRIMARY_ONLY);
        channel.updateFallbackOrder(List.of(primary.ref(), smsGate().ref()));
        providers.save(channel);

        // Act — SMSGATE was never saved, so its code has no provider row behind it
        ChannelConfig restored = providers.findChannel(Channel.SMS).orElseThrow();

        // Assert
        assertThat(restored.fallbackOrder()).containsExactly(primary.ref());
    }

    @Test
    @DisplayName("a routing policy keeps its match, action and enabled flag (FR-8.9)")
    void routingPolicyRoundTrips() {
        // Arrange
        RoutingPolicy policy = RoutingPolicy.of(
                RoutingPolicyId.newId(),
                RoutingPolicy.Match.ofTrafficClass(TrafficClass.CRITICAL_OTP),
                RoutingPolicy.Action.toProviders(Channel.SMS, List.of(ProviderCode.of("PLAYMOBILE"))),
                100);
        policy.disable();

        // Act
        providers.save(policy);
        List<RoutingPolicy> restored = providers.findPolicies();

        // Assert
        assertThat(restored).hasSize(1);
        RoutingPolicy stored = restored.getFirst();
        assertThat(stored.id()).isEqualTo(policy.id());
        assertThat(stored.priority()).isEqualTo(100);
        assertThat(stored.isEnabled()).isFalse();
        assertThat(stored.match()).isEqualTo(policy.match());
        assertThat(stored.action()).isEqualTo(policy.action());
    }

    @Test
    @DisplayName("a stream keeps its defaults, quota and quiet hours (FR-1.3, FR-2.4, TC-02)")
    void streamRoundTrips() {
        // Arrange
        Provider provider = providers.save(playmobile());
        Stream stream = Stream.register(
                StreamId.of("mobile-app"),
                "Mobile application",
                IntegrationType.KAFKA,
                new Stream.Defaults(Channel.SMS, provider.ref(), TrafficClass.TRANSACTIONAL, Priority.HIGH));
        stream.updateQuota(QuotaConfig.ofCounts(1_000L, 20_000L, QuotaExhaustionBehavior.BLOCK_AND_ALERT));
        stream.updateQuietHours(QuietHours.rejecting(LocalTime.of(22, 0), LocalTime.of(7, 0)));
        stream.updateCredentialsRef("vault://streams/mobile-app");
        stream.touch(Instant.parse("2026-08-08T10:15:30Z"));
        stream.suspend();

        // Act
        streams.save(stream);
        Stream restored = streams.findById(StreamId.of("mobile-app")).orElseThrow();

        // Assert
        assertThat(restored.name()).isEqualTo("Mobile application");
        assertThat(restored.status()).isEqualTo(StreamStatus.SUSPENDED);
        assertThat(restored.defaults().channel()).isEqualTo(Channel.SMS);
        assertThat(restored.defaults().provider()).isEqualTo(provider.ref());
        assertThat(restored.defaults().priority()).isEqualTo(Priority.HIGH);
        assertThat(restored.quota().dailyCountLimit()).contains(1_000L);
        assertThat(restored.quietHours()).isPresent();
        assertThat(restored.credentialsRef()).contains("vault://streams/mobile-app");
        assertThat(restored.lastActivityAt()).contains(Instant.parse("2026-08-08T10:15:30Z"));
    }

    @Test
    @DisplayName("saving a stream twice updates it instead of failing on the primary key")
    void streamSaveIsIdempotent() {
        // Arrange
        Stream stream =
                Stream.register(StreamId.of("core-banking"), "Core", IntegrationType.REST, Stream.Defaults.none());
        streams.save(stream);

        // Act
        stream.disable();
        streams.save(stream);

        // Assert
        assertThat(streams.findAll()).hasSize(1);
        assertThat(streams.findById(StreamId.of("core-banking")).orElseThrow().status())
                .isEqualTo(StreamStatus.DISABLED);
    }

    @Test
    @DisplayName("the routing configuration of a stream carries channels, providers, policies and defaults")
    void routingConfigurationIsAssembled() {
        // Arrange
        Provider provider = providers.save(playmobile());
        ChannelConfig channel = ChannelConfig.of(Channel.SMS, BalancingStrategy.ROUND_ROBIN);
        channel.updateFallbackOrder(List.of(provider.ref()));
        providers.save(channel);
        providers.save(RoutingPolicy.of(
                RoutingPolicyId.newId(),
                RoutingPolicy.Match.ofStream(StreamId.of("mobile-app")),
                RoutingPolicy.Action.toChannel(Channel.SMS),
                10));
        streams.save(Stream.register(
                StreamId.of("mobile-app"),
                "Mobile application",
                IntegrationType.KAFKA,
                Stream.Defaults.of(Channel.SMS, TrafficClass.NOTIFICATION)));

        // Act
        RoutingConfiguration configuration = providers.routingConfiguration(StreamId.of("mobile-app"));

        // Assert
        assertThat(configuration.channelConfig(Channel.SMS)).isPresent();
        assertThat(configuration.providersFor(Channel.SMS)).hasSize(1);
        assertThat(configuration.policies()).hasSize(1);
        assertThat(configuration.streamDefaults().channel()).isEqualTo(Channel.SMS);
        assertThat(configuration.streamDefaults().trafficClass()).isEqualTo(TrafficClass.NOTIFICATION);
    }

    @Test
    @DisplayName("an unknown stream yields a configuration with empty defaults instead of failing")
    void unknownStreamStillYieldsConfiguration() {
        // Arrange + Act
        RoutingConfiguration configuration = providers.routingConfiguration(StreamId.of("unknown-stream"));

        // Assert
        assertThat(configuration.streamDefaults()).isEqualTo(Stream.Defaults.none());
        assertThat(configuration.providers()).isEmpty();
    }

    private Provider playmobile() {
        return Provider.register(
                ProviderId.newId(),
                ProviderCode.of("PLAYMOBILE"),
                Channel.SMS,
                AdapterType.of("playmobile-http"),
                new Provider.Settings(
                        30,
                        Tariff.perSegment(Money.of(new BigDecimal("120.5000"), UZS)),
                        new RateLimit(50, 1_000, 5),
                        "vault://providers/playmobile",
                        true));
    }

    private Provider smsGate() {
        return Provider.register(
                ProviderId.newId(),
                ProviderCode.of("SMSGATE"),
                Channel.SMS,
                AdapterType.of("smsgate-http"),
                Provider.Settings.defaults());
    }
}
