package uz.hamkorbank.commhub.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static uz.hamkorbank.commhub.domain.DomainFixtures.NOW;
import static uz.hamkorbank.commhub.domain.DomainFixtures.envelope;
import static uz.hamkorbank.commhub.domain.DomainFixtures.msisdn;
import static uz.hamkorbank.commhub.domain.DomainFixtures.routingConfiguration;
import static uz.hamkorbank.commhub.domain.DomainFixtures.smsChannel;
import static uz.hamkorbank.commhub.domain.DomainFixtures.smsMessage;
import static uz.hamkorbank.commhub.domain.DomainFixtures.smsProvider;
import static uz.hamkorbank.commhub.domain.DomainFixtures.uzs;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.hamkorbank.commhub.domain.exception.DomainValidationException;
import uz.hamkorbank.commhub.domain.model.ChannelConfig;
import uz.hamkorbank.commhub.domain.model.ChannelPlan;
import uz.hamkorbank.commhub.domain.model.Message;
import uz.hamkorbank.commhub.domain.model.Provider;
import uz.hamkorbank.commhub.domain.model.RoutingPolicy;
import uz.hamkorbank.commhub.domain.model.Stream;
import uz.hamkorbank.commhub.domain.model.Timing;
import uz.hamkorbank.commhub.domain.model.content.MessageContents;
import uz.hamkorbank.commhub.domain.model.content.PushContent;
import uz.hamkorbank.commhub.domain.model.content.SmsContent;
import uz.hamkorbank.commhub.domain.model.type.BalancingStrategy;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.ProviderHealthStatus;
import uz.hamkorbank.commhub.domain.model.type.PushPlatform;
import uz.hamkorbank.commhub.domain.model.type.RejectionReason;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;
import uz.hamkorbank.commhub.domain.model.vo.AdapterType;
import uz.hamkorbank.commhub.domain.model.vo.EmailAddress;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.model.vo.ProviderId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderRef;
import uz.hamkorbank.commhub.domain.model.vo.PushToken;
import uz.hamkorbank.commhub.domain.model.vo.Recipient;
import uz.hamkorbank.commhub.domain.model.vo.RoutingPolicyId;

/** Channel and provider selection with balancing and failover (MP-05, FR-2.2, FR-2.3, FR-2.4). */
class RouterTest {

    private Router router;

    @BeforeEach
    void setUp() {
        router = new Router(new FallbackChain());
    }

    @Test
    @DisplayName("FR-2.2: the primary provider is chosen and the rest become the fallback order")
    void primaryProviderWithFallbacks() {
        // Arrange
        Provider playmobile = smsProvider("PLAYMOBILE");
        Provider smsgate = smsProvider("SMSGATE");
        RoutingConfiguration configuration =
                routingConfiguration(BalancingStrategy.PRIMARY_ONLY, List.of(playmobile, smsgate), List.of());

        // Act
        RoutingResult result = router.route(RoutingRequest.of(smsMessage(), 1, 0L), configuration);

        // Assert
        RoutingResult.Routed routed = result.routed().orElseThrow();
        assertThat(result.isRouted()).isTrue();
        assertThat(routed.channel()).isEqualTo(Channel.SMS);
        assertThat(routed.provider()).isEqualTo(playmobile.ref());
        assertThat(routed.fallbackProviders()).containsExactly(smsgate.ref());
        assertThat(routed.hasFallback()).isTrue();
        assertThat(routed.attemptOrder()).containsExactly(playmobile.ref(), smsgate.ref());
        assertThat(routed.strategy()).isEqualTo(BalancingStrategy.PRIMARY_ONLY);
    }

    @Test
    @DisplayName("FR-2.3: round-robin rotates over the selectable providers")
    void roundRobinRotates() {
        // Arrange
        Provider first = smsProvider("PLAYMOBILE");
        Provider second = smsProvider("SMSGATE");
        RoutingConfiguration configuration =
                routingConfiguration(BalancingStrategy.ROUND_ROBIN, List.of(first, second), List.of());

        // Act + Assert
        assertThat(chosen(configuration, 0L)).isEqualTo(first.ref());
        assertThat(chosen(configuration, 1L)).isEqualTo(second.ref());
        assertThat(chosen(configuration, 2L)).isEqualTo(first.ref());
        assertThat(chosen(configuration, 3L)).isEqualTo(second.ref());
    }

    @Test
    @DisplayName("FR-2.3: weighted balancing follows the provider weights")
    void weightedBalancingFollowsWeights() {
        // Arrange
        Provider heavy = smsProvider("PLAYMOBILE", 3, uzs("25"));
        Provider light = smsProvider("SMSGATE", 1, uzs("25"));
        RoutingConfiguration configuration =
                routingConfiguration(BalancingStrategy.WEIGHTED, List.of(heavy, light), List.of());

        // Act + Assert
        assertThat(chosen(configuration, 0L)).isEqualTo(heavy.ref());
        assertThat(chosen(configuration, 1L)).isEqualTo(heavy.ref());
        assertThat(chosen(configuration, 2L)).isEqualTo(heavy.ref());
        assertThat(chosen(configuration, 3L)).isEqualTo(light.ref());
        assertThat(chosen(configuration, 4L)).isEqualTo(heavy.ref());
    }

    @Test
    @DisplayName("FR-2.3: least-cost routing compares the price of the actual segment count")
    void leastCostRoutingComparesSegmentPrice() {
        // Arrange
        Provider expensive = smsProvider("PLAYMOBILE", 10, uzs("30"));
        Provider cheap = smsProvider("SMSGATE", 10, uzs("20"));
        RoutingConfiguration configuration =
                routingConfiguration(BalancingStrategy.LEAST_COST, List.of(expensive, cheap), List.of());

        // Act
        RoutingResult.Routed routed = router.route(RoutingRequest.of(smsMessage(), 3, 0L), configuration)
                .routed()
                .orElseThrow();

        // Assert
        assertThat(routed.provider()).isEqualTo(cheap.ref());
        assertThat(routed.fallbackProviders()).containsExactly(expensive.ref());
    }

    @Test
    @DisplayName("least-cost falls back to the configured order when no provider has a tariff")
    void leastCostWithoutTariffsKeepsTheOrder() {
        // Arrange
        Provider first = smsProvider("PLAYMOBILE");
        Provider second = smsProvider("SMSGATE");
        RoutingConfiguration configuration =
                routingConfiguration(BalancingStrategy.LEAST_COST, List.of(first, second), List.of());

        // Act + Assert
        assertThat(chosen(configuration, 0L)).isEqualTo(first.ref());
    }

    @Test
    @DisplayName("FR-6.3: a provider that was already tried is skipped on re-routing")
    void failoverSkipsExhaustedProviders() {
        // Arrange
        Provider playmobile = smsProvider("PLAYMOBILE");
        Provider smsgate = smsProvider("SMSGATE");
        RoutingConfiguration configuration =
                routingConfiguration(BalancingStrategy.PRIMARY_ONLY, List.of(playmobile, smsgate), List.of());
        RoutingRequest request = RoutingRequest.of(smsMessage(), 1, 0L).excluding(Set.of(playmobile.ref()));

        // Act
        RoutingResult result = router.route(request, configuration);

        // Assert
        assertThat(result.routed().orElseThrow().provider()).isEqualTo(smsgate.ref());
        assertThat(result.routed().orElseThrow().hasFallback()).isFalse();
    }

    @Test
    @DisplayName("FR-2.2: without a selectable provider the message has no route")
    void noSelectableProviderMeansNoRoute() {
        // Arrange
        Provider disabled = smsProvider("PLAYMOBILE");
        disabled.markHealth(ProviderHealthStatus.DOWN);
        RoutingConfiguration configuration =
                routingConfiguration(BalancingStrategy.PRIMARY_ONLY, List.of(disabled), List.of());

        // Act
        RoutingResult result = router.route(RoutingRequest.of(smsMessage(), 1, 0L), configuration);

        // Assert
        assertThat(result.isRouted()).isFalse();
        assertThat(result.routed()).isEmpty();
        assertThat(((RoutingResult.NoRoute) result).reason()).isEqualTo(RejectionReason.NO_ROUTE_AVAILABLE);
        assertThat(((RoutingResult.NoRoute) result).detail()).contains("no selectable provider");
    }

    @Test
    @DisplayName("an unconfigured or disabled channel yields no route")
    void unconfiguredOrDisabledChannelMeansNoRoute() {
        // Arrange
        Provider playmobile = smsProvider("PLAYMOBILE");
        RoutingConfiguration withoutChannels = RoutingConfiguration.of(Map.of(), List.of(playmobile), List.of());
        ChannelConfig disabledChannel = smsChannel(BalancingStrategy.PRIMARY_ONLY, List.of(playmobile));
        disabledChannel.disable();
        RoutingConfiguration withDisabledChannel =
                RoutingConfiguration.of(Map.of(Channel.SMS, disabledChannel), List.of(playmobile), List.of());

        // Act
        RoutingResult noChannel = router.route(RoutingRequest.of(smsMessage(), 1, 0L), withoutChannels);
        RoutingResult disabled = router.route(RoutingRequest.of(smsMessage(), 1, 0L), withDisabledChannel);

        // Assert
        assertThat(((RoutingResult.NoRoute) noChannel).detail()).contains("not configured");
        assertThat(((RoutingResult.NoRoute) disabled).detail()).contains("DISABLED");
    }

    @Test
    @DisplayName("a recipient without an address for the planned channel yields no route")
    void unreachableRecipientMeansNoRoute() {
        // Arrange
        Message emailOnlyRecipient = Message.accept(
                envelope("sms-1", TrafficClass.TRANSACTIONAL),
                Recipient.ofEmail(EmailAddress.of("ivan@hamkorbank.uz")),
                ChannelPlan.explicitChannel(Channel.SMS),
                MessageContents.of(SmsContent.of("text")),
                null,
                Timing.immediate(),
                NOW);
        RoutingConfiguration configuration =
                routingConfiguration(BalancingStrategy.PRIMARY_ONLY, List.of(smsProvider("PLAYMOBILE")), List.of());

        // Act
        RoutingResult result = router.route(RoutingRequest.of(emailOnlyRecipient, 1, 0L), configuration);

        // Assert
        assertThat(((RoutingResult.NoRoute) result).detail()).contains("no usable address");
    }

    @Test
    @DisplayName("FR-2.4: the stream default channel decides when the plan leaves the choice open")
    void streamDefaultChannelDecides() {
        // Arrange
        Message multiChannel = multiChannelMessage();
        Provider fcm = pushProvider();
        ChannelConfig pushChannel = ChannelConfig.of(Channel.PUSH, BalancingStrategy.PRIMARY_ONLY);
        pushChannel.updateFallbackOrder(List.of(fcm.ref()));
        Provider playmobile = smsProvider("PLAYMOBILE");
        RoutingConfiguration configuration = new RoutingConfiguration(
                Map.of(
                        Channel.SMS,
                        smsChannel(BalancingStrategy.PRIMARY_ONLY, List.of(playmobile)),
                        Channel.PUSH,
                        pushChannel),
                List.of(playmobile, fcm),
                List.of(),
                Stream.Defaults.of(Channel.PUSH, TrafficClass.NOTIFICATION));

        // Act
        RoutingResult result = router.route(RoutingRequest.of(multiChannel, 1, 0L), configuration);

        // Assert
        assertThat(result.routed().orElseThrow().channel()).isEqualTo(Channel.PUSH);
        assertThat(result.routed().orElseThrow().provider()).isEqualTo(fcm.ref());
    }

    @Test
    @DisplayName("FR-8.9: a routing policy overrides channel, provider order and strategy")
    void policyOverridesChannelAndProviders() {
        // Arrange
        Provider playmobile = smsProvider("PLAYMOBILE", 10, uzs("30"));
        Provider smsgate = smsProvider("SMSGATE", 10, uzs("20"));
        RoutingPolicy policy = RoutingPolicy.of(
                RoutingPolicyId.newId(),
                RoutingPolicy.Match.ofTrafficClass(TrafficClass.TRANSACTIONAL),
                new RoutingPolicy.Action(
                        Channel.SMS, List.of(ProviderCode.of("SMSGATE")), BalancingStrategy.PRIMARY_ONLY),
                100);
        RoutingConfiguration configuration =
                routingConfiguration(BalancingStrategy.LEAST_COST, List.of(playmobile, smsgate), List.of(policy));

        // Act
        RoutingResult.Routed routed = router.route(RoutingRequest.of(smsMessage(), 1, 0L), configuration)
                .routed()
                .orElseThrow();

        // Assert
        assertThat(routed.provider()).isEqualTo(smsgate.ref());
        assertThat(routed.fallbackProviders()).isEmpty();
        assertThat(routed.strategy()).isEqualTo(BalancingStrategy.PRIMARY_ONLY);
    }

    @Test
    @DisplayName("the highest-priority matching policy wins")
    void highestPriorityPolicyWins() {
        // Arrange
        Provider playmobile = smsProvider("PLAYMOBILE");
        Provider smsgate = smsProvider("SMSGATE");
        RoutingPolicy low = RoutingPolicy.of(
                RoutingPolicyId.newId(),
                RoutingPolicy.Match.any(),
                RoutingPolicy.Action.toProviders(Channel.SMS, List.of(ProviderCode.of("PLAYMOBILE"))),
                1);
        RoutingPolicy high = RoutingPolicy.of(
                RoutingPolicyId.newId(),
                RoutingPolicy.Match.any(),
                RoutingPolicy.Action.toProviders(Channel.SMS, List.of(ProviderCode.of("SMSGATE"))),
                10);
        RoutingConfiguration configuration =
                routingConfiguration(BalancingStrategy.PRIMARY_ONLY, List.of(playmobile, smsgate), List.of(low, high));

        // Act + Assert
        assertThat(chosen(configuration, 0L)).isEqualTo(smsgate.ref());
    }

    @Test
    @DisplayName("the configuration snapshot answers provider and policy lookups")
    void configurationLookups() {
        // Arrange
        Provider playmobile = smsProvider("PLAYMOBILE");
        RoutingConfiguration configuration =
                routingConfiguration(BalancingStrategy.PRIMARY_ONLY, List.of(playmobile), List.of());

        // Act + Assert
        assertThat(configuration.provider(playmobile.id())).contains(playmobile);
        assertThat(configuration.provider(ProviderCode.of("PLAYMOBILE"))).contains(playmobile);
        assertThat(configuration.provider(ProviderCode.of("SMSGATE"))).isEmpty();
        assertThat(configuration.providersFor(Channel.SMS)).containsExactly(playmobile);
        assertThat(configuration.providersFor(Channel.EMAIL)).isEmpty();
        assertThat(configuration.channelConfig(Channel.SMS)).isPresent();
        assertThat(configuration.channelConfig(Channel.EMAIL)).isEmpty();
        assertThat(configuration.streamDefaults()).isEqualTo(Stream.Defaults.none());
        assertThat(configuration
                        .withStreamDefaults(Stream.Defaults.of(Channel.SMS, TrafficClass.NOTIFICATION))
                        .streamDefaults()
                        .channelOptional())
                .contains(Channel.SMS);
        assertThat(new RoutingConfiguration(null, null, null, null).providers()).isEmpty();
    }

    @Test
    @DisplayName("a routing decision validates its own consistency")
    void routedResultIsValidated() {
        // Arrange
        ProviderRef sms = smsProvider("PLAYMOBILE").ref();
        ProviderRef push = pushProvider().ref();

        // Act + Assert
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(
                        () -> new RoutingResult.Routed(Channel.SMS, push, List.of(), BalancingStrategy.PRIMARY_ONLY))
                .withMessageContaining("does not serve channel");
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(
                        () -> new RoutingResult.Routed(Channel.SMS, sms, List.of(sms), BalancingStrategy.PRIMARY_ONLY))
                .withMessageContaining("must not repeat");
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> router.route(
                        null,
                        routingConfiguration(
                                BalancingStrategy.PRIMARY_ONLY, List.of(smsProvider("PLAYMOBILE")), List.of())));
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> router.route(RoutingRequest.of(smsMessage(), 1, 0L), null));
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> RoutingRequest.of(smsMessage(), -1, 0L));
    }

    private ProviderRef chosen(RoutingConfiguration configuration, long rotation) {
        return router.route(RoutingRequest.of(smsMessage(), 1, rotation), configuration)
                .routed()
                .orElseThrow()
                .provider();
    }

    private static Message multiChannelMessage() {
        return Message.accept(
                envelope("multi-1", TrafficClass.NOTIFICATION),
                new Recipient(null, msisdn(), null, List.of(PushToken.of("device-token", PushPlatform.ANDROID))),
                ChannelPlan.moduleChoice(),
                MessageContents.of(SmsContent.of("text"), PushContent.of("Title", "Body")),
                null,
                Timing.immediate(),
                NOW);
    }

    private static Provider pushProvider() {
        return Provider.register(
                ProviderId.newId(),
                ProviderCode.of("FCM"),
                Channel.PUSH,
                AdapterType.of("fcm-http-v1"),
                Provider.Settings.defaults());
    }
}
