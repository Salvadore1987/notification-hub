package uz.hamkorbank.commhub.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.NOW;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.STREAM_ID;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.smsChannel;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.smsProvider;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.stream;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import uz.hamkorbank.commhub.application.dto.ChannelView;
import uz.hamkorbank.commhub.application.dto.DeployedAdapterView;
import uz.hamkorbank.commhub.application.dto.ProviderView;
import uz.hamkorbank.commhub.application.dto.RoutingPolicyView;
import uz.hamkorbank.commhub.application.dto.StreamView;
import uz.hamkorbank.commhub.application.exception.ConfigurationConflictException;
import uz.hamkorbank.commhub.application.exception.NotFoundException;
import uz.hamkorbank.commhub.application.mapper.ConfigMapperImpl;
import uz.hamkorbank.commhub.application.port.in.command.ChannelStateCommand;
import uz.hamkorbank.commhub.application.port.in.command.ConfigureChannelCommand;
import uz.hamkorbank.commhub.application.port.in.command.DeleteProviderCommand;
import uz.hamkorbank.commhub.application.port.in.command.ProviderStateCommand;
import uz.hamkorbank.commhub.application.port.in.command.RegisterProviderCommand;
import uz.hamkorbank.commhub.application.port.in.command.RegisterStreamCommand;
import uz.hamkorbank.commhub.application.port.in.command.RoutingPolicyStateCommand;
import uz.hamkorbank.commhub.application.port.in.command.SaveRoutingPolicyCommand;
import uz.hamkorbank.commhub.application.port.in.command.UpdateProviderCommand;
import uz.hamkorbank.commhub.application.port.in.command.UpdateStreamCommand;
import uz.hamkorbank.commhub.application.port.out.AuditEntry;
import uz.hamkorbank.commhub.application.port.out.AuditPort;
import uz.hamkorbank.commhub.application.port.out.ClockPort;
import uz.hamkorbank.commhub.application.port.out.ProviderConfigRepository;
import uz.hamkorbank.commhub.application.port.out.StreamRepository;
import uz.hamkorbank.commhub.application.port.out.provider.ProviderPort;
import uz.hamkorbank.commhub.application.service.support.ConfigAuditor;
import uz.hamkorbank.commhub.application.service.support.ProviderGateway;
import uz.hamkorbank.commhub.domain.model.Actor;
import uz.hamkorbank.commhub.domain.model.ChannelConfig;
import uz.hamkorbank.commhub.domain.model.Provider;
import uz.hamkorbank.commhub.domain.model.QuietHours;
import uz.hamkorbank.commhub.domain.model.QuotaConfig;
import uz.hamkorbank.commhub.domain.model.RateLimit;
import uz.hamkorbank.commhub.domain.model.RoutingPolicy;
import uz.hamkorbank.commhub.domain.model.Stream;
import uz.hamkorbank.commhub.domain.model.type.BalancingStrategy;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.ChannelStatus;
import uz.hamkorbank.commhub.domain.model.type.IntegrationType;
import uz.hamkorbank.commhub.domain.model.type.QuotaExhaustionBehavior;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;
import uz.hamkorbank.commhub.domain.model.vo.AdapterType;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.model.vo.ProviderId;
import uz.hamkorbank.commhub.domain.model.vo.RoutingPolicyId;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;

/** Configuration of providers, channels, streams and routing policies (FR-2.1…FR-2.7, FR-8.9). */
class ConfigurationUseCasesTest {

    private static final Actor OPERATOR = Actor.operator("admin");

    private ProviderConfigRepository configuration;
    private StreamRepository streams;
    private AuditPort audit;
    private ConfigAuditor auditor;
    private ConfigMapperImpl mapper;

    private ProviderConfigService providerService;
    private ChannelConfigService channelService;
    private StreamConfigService streamService;
    private RoutingPolicyConfigService policyService;
    private RoutingConfigQueryService queryService;

    @BeforeEach
    void setUp() {
        configuration = mock(ProviderConfigRepository.class);
        streams = mock(StreamRepository.class);
        audit = mock(AuditPort.class);
        ClockPort clock = mock(ClockPort.class);
        when(clock.now()).thenReturn(NOW);
        when(configuration.endpointConfig(any())).thenReturn(Map.of());
        when(configuration.findChannels()).thenReturn(List.of());
        auditor = new ConfigAuditor(audit, clock);
        mapper = new ConfigMapperImpl();
        providerService = new ProviderConfigService(configuration, mapper, auditor);
        channelService = new ChannelConfigService(configuration, mapper, auditor);
        streamService = new StreamConfigService(streams, clock, mapper, auditor);
        policyService = new RoutingPolicyConfigService(configuration, mapper, auditor);
        queryService = new RoutingConfigQueryService(configuration, streams, clock, mapper);
    }

    @Test
    @DisplayName("FR-2.1: registering a provider stores the profile, its quota and its endpoint config")
    void registersProvider() {
        // Arrange
        when(configuration.findProviderByCode(ProviderCode.of("PLAYMOBILE"))).thenReturn(Optional.empty());
        RegisterProviderCommand command = new RegisterProviderCommand(
                OPERATOR,
                ProviderCode.of("PLAYMOBILE"),
                Channel.SMS,
                AdapterType.of("playmobile-http"),
                Provider.Settings.defaults().withWeight(20),
                QuotaConfig.ofCounts(1_000L, null, QuotaExhaustionBehavior.BLOCK_AND_ALERT),
                Map.of("base-url", "https://send.smsxabar.uz"));

        // Act
        ProviderView view = providerService.register(command);

        // Assert
        ArgumentCaptor<Provider> saved = ArgumentCaptor.forClass(Provider.class);
        verify(configuration).save(saved.capture());
        assertThat(saved.getValue().weight()).isEqualTo(20);
        assertThat(saved.getValue().quota().dailyCountLimit()).contains(1_000L);
        verify(configuration).saveEndpointConfig(saved.getValue().id(), Map.of("base-url", "https://send.smsxabar.uz"));
        assertThat(view.state().selectable()).isTrue();
        assertThat(view.state().endpointConfig()).containsEntry("base-url", "https://send.smsxabar.uz");
        assertThat(auditAction()).isEqualTo("provider.register");
    }

    @Test
    @DisplayName("FR-2.1: a provider code is unique")
    void refusesDuplicateProviderCode() {
        // Arrange
        Provider existing = smsProvider("PLAYMOBILE");
        when(configuration.findProviderByCode(ProviderCode.of("PLAYMOBILE"))).thenReturn(Optional.of(existing));

        // Act + Assert
        assertThatExceptionOfType(ConfigurationConflictException.class)
                .isThrownBy(() -> providerService.register(new RegisterProviderCommand(
                        OPERATOR,
                        ProviderCode.of("PLAYMOBILE"),
                        Channel.SMS,
                        AdapterType.of("playmobile-http"),
                        null,
                        null,
                        null)))
                .withMessageContaining("already exists");
        verify(configuration, never()).save(any(Provider.class));
    }

    @Test
    @DisplayName("FR-2.5, FR-2.6: an update patches only the fields it carries")
    void updatesProviderPartially() {
        // Arrange
        Provider provider = smsProvider("SMSGATE");
        when(configuration.findProvider(provider.id())).thenReturn(Optional.of(provider));

        // Act
        ProviderView view = providerService.update(new UpdateProviderCommand(
                OPERATOR, provider.id(), 30, null, new RateLimit(50, 0, 45), null, null, null));

        // Assert
        assertThat(view.weight()).isEqualTo(30);
        assertThat(view.rateLimit().perRecipientPerHour()).isEqualTo(45);
        assertThat(view.tariffOptional()).isPresent();
        verify(configuration, never()).saveEndpointConfig(any(), any());
    }

    @Test
    @DisplayName("FR-2.7: disabling and maintenance take a provider out of routing without deleting it")
    void switchesProviderState() {
        // Arrange
        Provider provider = smsProvider("SMSGATE");
        when(configuration.findProvider(provider.id())).thenReturn(Optional.of(provider));

        // Act
        ProviderView disabled = providerService.changeState(new ProviderStateCommand(
                OPERATOR, provider.id(), ProviderStateCommand.ProviderState.DISABLED, "contract suspended"));
        ProviderView maintenance = providerService.changeState(
                ProviderStateCommand.of(OPERATOR, provider.id(), ProviderStateCommand.ProviderState.MAINTENANCE));
        ProviderView enabled = providerService.changeState(
                ProviderStateCommand.of(OPERATOR, provider.id(), ProviderStateCommand.ProviderState.ENABLED));

        // Assert
        assertThat(disabled.state().enabled()).isFalse();
        assertThat(disabled.state().selectable()).isFalse();
        assertThat(maintenance.state().maintenance()).isTrue();
        assertThat(maintenance.state().selectable()).isFalse();
        assertThat(enabled.state().selectable()).isTrue();
        assertThat(auditAction()).isEqualTo("provider.state");
    }

    @Test
    @DisplayName("FR-2.2: a provider still in a fallback order cannot be deleted")
    void refusesDeletingReferencedProvider() {
        // Arrange
        Provider provider = smsProvider("SMSGATE");
        when(configuration.findProvider(provider.id())).thenReturn(Optional.of(provider));
        when(configuration.findChannels()).thenReturn(List.of(smsChannel(List.of(provider))));

        // Act + Assert
        assertThatExceptionOfType(ConfigurationConflictException.class)
                .isThrownBy(() -> providerService.delete(new DeleteProviderCommand(OPERATOR, provider.id(), null)))
                .withMessageContaining("fallback order");
        verify(configuration, never()).deleteProvider(any());
    }

    @Test
    @DisplayName("FR-2.1: an unreferenced provider is deleted and journalled")
    void deletesUnreferencedProvider() {
        // Arrange
        Provider provider = smsProvider("SMSGATE");
        when(configuration.findProvider(provider.id())).thenReturn(Optional.of(provider));

        // Act
        providerService.delete(new DeleteProviderCommand(OPERATOR, provider.id(), "replaced"));

        // Assert
        verify(configuration).deleteProvider(provider.id());
        assertThat(auditAction()).isEqualTo("provider.delete");
    }

    @Test
    @DisplayName("a command naming an unknown provider is a 404, not a silent no-op")
    void unknownProviderIsNotFound() {
        // Arrange
        ProviderId unknown = ProviderId.newId();
        when(configuration.findProvider(unknown)).thenReturn(Optional.empty());

        // Act + Assert
        assertThatExceptionOfType(NotFoundException.class)
                .isThrownBy(() -> providerService.changeState(
                        ProviderStateCommand.of(OPERATOR, unknown, ProviderStateCommand.ProviderState.ENABLED)));
    }

    @Test
    @DisplayName("FR-2.2, FR-2.3: configuring a channel resolves the codes into an ordered chain")
    void configuresChannel() {
        // Arrange
        Provider primary = smsProvider("PLAYMOBILE");
        Provider reserve = smsProvider("SMSGATE");
        when(configuration.findChannel(Channel.SMS)).thenReturn(Optional.empty());
        when(configuration.findProviders(Channel.SMS)).thenReturn(List.of(primary, reserve));

        // Act
        ChannelView view = channelService.configure(new ConfigureChannelCommand(
                OPERATOR,
                Channel.SMS,
                BalancingStrategy.LEAST_COST,
                List.of(reserve.code(), primary.code()),
                null,
                QuotaConfig.ofCounts(null, 500_000L, QuotaExhaustionBehavior.ALERT_ONLY)));

        // Assert
        assertThat(view.fallbackOrder()).containsExactly(reserve.code(), primary.code());
        assertThat(view.balancingStrategy()).isEqualTo(BalancingStrategy.LEAST_COST);
        assertThat(view.quota().monthlyCountLimit()).contains(500_000L);
        assertThat(view.available()).isTrue();
        assertThat(auditAction()).isEqualTo("channel.configure");
    }

    @Test
    @DisplayName("a fallback order naming a provider of another channel is refused")
    void refusesUnknownProviderInOrder() {
        // Arrange
        when(configuration.findChannel(Channel.SMS)).thenReturn(Optional.empty());
        when(configuration.findProviders(Channel.SMS)).thenReturn(List.of());

        // Act + Assert
        assertThatExceptionOfType(ConfigurationConflictException.class)
                .isThrownBy(() -> channelService.configure(new ConfigureChannelCommand(
                        OPERATOR,
                        Channel.SMS,
                        BalancingStrategy.PRIMARY_ONLY,
                        List.of(ProviderCode.of("FCM")),
                        null,
                        null)))
                .withMessageContaining("does not serve channel");
    }

    @Test
    @DisplayName("FR-2.7: a channel can be put into maintenance and back at runtime")
    void switchesChannelState() {
        // Arrange
        ChannelConfig config = smsChannel(List.of(smsProvider("PLAYMOBILE")));
        when(configuration.findChannel(Channel.SMS)).thenReturn(Optional.of(config));

        // Act
        ChannelView maintenance = channelService.changeState(
                new ChannelStateCommand(OPERATOR, Channel.SMS, ChannelStatus.MAINTENANCE, "provider migration"));
        ChannelView active =
                channelService.changeState(ChannelStateCommand.of(OPERATOR, Channel.SMS, ChannelStatus.ACTIVE));

        // Assert
        assertThat(maintenance.status()).isEqualTo(ChannelStatus.MAINTENANCE);
        assertThat(maintenance.available()).isFalse();
        assertThat(active.available()).isTrue();
    }

    @Test
    @DisplayName("FR-2.4, TC-02: a stream is registered with its defaults, quota and request limit")
    void registersStream() {
        // Arrange
        when(streams.findById(STREAM_ID)).thenReturn(Optional.empty());

        // Act
        StreamView view = streamService.register(new RegisterStreamCommand(
                OPERATOR,
                STREAM_ID,
                "Mobile application",
                IntegrationType.REST,
                Stream.Defaults.of(Channel.SMS, TrafficClass.CRITICAL_OTP)
                        .withBalancingStrategy(BalancingStrategy.LEAST_COST),
                QuotaConfig.ofCounts(10_000L, null, QuotaExhaustionBehavior.BLOCK_AND_ALERT),
                null,
                new RateLimit(500, 0, 0)));

        // Assert
        assertThat(view.defaults().trafficClass()).isEqualTo(TrafficClass.CRITICAL_OTP);
        assertThat(view.defaults().balancingStrategyOptional()).contains(BalancingStrategy.LEAST_COST);
        assertThat(view.limits().rateLimit().tps()).isEqualTo(500);
        assertThat(view.limits().quota().dailyCountLimit()).contains(10_000L);
        verify(streams).save(any(Stream.class));
        assertThat(auditAction()).isEqualTo("stream.register");
    }

    @Test
    @DisplayName("a stream id is registered once")
    void refusesDuplicateStream() {
        // Arrange
        when(streams.findById(STREAM_ID)).thenReturn(Optional.of(stream()));

        // Act + Assert
        assertThatExceptionOfType(ConfigurationConflictException.class)
                .isThrownBy(() -> streamService.register(new RegisterStreamCommand(
                        OPERATOR, STREAM_ID, "Mobile application", IntegrationType.REST, null, null, null, null)));
    }

    @Test
    @DisplayName("FR-5.3: quiet hours of a stream are cleared explicitly, not by omission")
    void updatesStream() {
        // Arrange
        Stream existing = stream();
        existing.updateQuietHours(QuietHours.deferring(LocalTime.of(22, 0), LocalTime.of(8, 0)));
        when(streams.findById(STREAM_ID)).thenReturn(Optional.of(existing));

        // Act
        StreamView untouched = streamService.update(
                UpdateStreamCommand.ofDefaults(OPERATOR, STREAM_ID, Stream.Defaults.of(Channel.SMS, null)));
        StreamView cleared = streamService.update(
                new UpdateStreamCommand(OPERATOR, STREAM_ID, null, null, null, true, null, "vault:ibank"));

        // Assert
        assertThat(untouched.limits().quietHoursOptional()).isPresent();
        assertThat(cleared.limits().quietHoursOptional()).isEmpty();
        assertThat(auditAction()).isEqualTo("stream.update");
    }

    @Test
    @DisplayName("updating an unregistered stream is a 404")
    void unknownStreamIsNotFound() {
        // Arrange
        when(streams.findById(StreamId.of("ghost"))).thenReturn(Optional.empty());

        // Act + Assert
        assertThatExceptionOfType(NotFoundException.class)
                .isThrownBy(() ->
                        streamService.update(UpdateStreamCommand.ofDefaults(OPERATOR, StreamId.of("ghost"), null)));
    }

    @Test
    @DisplayName("FR-8.9: a routing rule is created, disabled and deleted")
    void managesRoutingPolicies() {
        // Arrange
        SaveRoutingPolicyCommand create = new SaveRoutingPolicyCommand(
                OPERATOR,
                null,
                RoutingPolicy.Match.ofTrafficClass(TrafficClass.CRITICAL_OTP),
                RoutingPolicy.Action.toProviders(Channel.SMS, List.of(ProviderCode.of("PLAYMOBILE"))),
                100);

        // Act
        RoutingPolicyView created = policyService.save(create);
        RoutingPolicyId policyId = created.policyId();
        when(configuration.findPolicy(policyId))
                .thenReturn(
                        Optional.of(RoutingPolicy.of(policyId, create.match(), create.action(), create.priority())));
        RoutingPolicyView disabled =
                policyService.changeState(new RoutingPolicyStateCommand(OPERATOR, policyId, false));
        policyService.delete(new RoutingPolicyStateCommand(OPERATOR, policyId, false));

        // Assert
        assertThat(created.priority()).isEqualTo(100);
        assertThat(created.enabled()).isTrue();
        assertThat(disabled.enabled()).isFalse();
        verify(configuration).deletePolicy(policyId);
        assertThat(auditAction()).isEqualTo("routing_policy.delete");
    }

    @Test
    @DisplayName("§11.2: the read side lists providers, channels, streams and policies")
    void listsConfiguration() {
        // Arrange
        Provider provider = smsProvider("PLAYMOBILE");
        when(configuration.findAllProviders()).thenReturn(List.of(provider));
        when(configuration.findChannels()).thenReturn(List.of(smsChannel(List.of(provider))));
        when(configuration.findPolicies())
                .thenReturn(List.of(RoutingPolicy.of(
                        RoutingPolicyId.newId(),
                        RoutingPolicy.Match.any(),
                        RoutingPolicy.Action.toChannel(Channel.SMS),
                        0)));
        when(streams.findAll()).thenReturn(List.of(stream()));

        // Act + Assert
        assertThat(queryService.providers()).singleElement().satisfies(view -> assertThat(view.code())
                .isEqualTo(provider.code()));
        assertThat(queryService.channels()).hasSize(1);
        assertThat(queryService.streams()).singleElement().satisfies(view -> assertThat(view.streamId())
                .isEqualTo(STREAM_ID));
        assertThat(queryService.policies()).hasSize(1);
    }

    @Test
    @DisplayName("AR-04, §11.2: the deployed adapters are listed once each, by channel then type")
    void listsDeployedAdapters() {
        // Arrange — two SMS adapters, one of them registered twice, and an email one
        List<ProviderPort> deployed = List.of(
                adapterPort(Channel.SMS, "smsgate-http"),
                adapterPort(Channel.EMAIL, "smtp"),
                adapterPort(Channel.SMS, "playmobile-http"),
                adapterPort(Channel.SMS, "playmobile-http"));
        ProviderGateway gateway = mock(ProviderGateway.class);
        when(gateway.deployedAdapters()).thenReturn(deployed);

        // Act
        List<DeployedAdapterView> adapters = new DeployedAdapterQueryService(gateway, mapper).adapters();

        // Assert — order is what the operator reads, and a duplicate pair is one option, not two
        assertThat(adapters)
                .extracting(
                        view -> view.channel().name() + "/" + view.adapterType().value())
                .containsExactly("EMAIL/smtp", "SMS/playmobile-http", "SMS/smsgate-http");
    }

    @Test
    @DisplayName("AR-04: a contour with no provider enabled offers nothing")
    void listsNoAdapterWhenNoneIsDeployed() {
        // Arrange
        ProviderGateway gateway = mock(ProviderGateway.class);
        when(gateway.deployedAdapters()).thenReturn(List.of());

        // Act
        List<DeployedAdapterView> adapters = new DeployedAdapterQueryService(gateway, mapper).adapters();

        // Assert
        assertThat(adapters).isEmpty();
    }

    private static ProviderPort adapterPort(Channel channel, String adapterType) {
        ProviderPort port = mock(ProviderPort.class);
        when(port.channel()).thenReturn(channel);
        when(port.adapterType()).thenReturn(AdapterType.of(adapterType));
        return port;
    }

    private String auditAction() {
        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit, org.mockito.Mockito.atLeastOnce()).write(entry.capture());
        return entry.getValue().action();
    }
}
