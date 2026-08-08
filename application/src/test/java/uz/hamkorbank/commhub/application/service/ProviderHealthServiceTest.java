package uz.hamkorbank.commhub.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.NOW;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.smsProvider;

import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import uz.hamkorbank.commhub.application.dto.ProviderHealthResult;
import uz.hamkorbank.commhub.application.policy.ProviderHealthPolicy;
import uz.hamkorbank.commhub.application.port.in.command.CheckProviderHealthCommand;
import uz.hamkorbank.commhub.application.port.out.ClockPort;
import uz.hamkorbank.commhub.application.port.out.ProviderConfigRepository;
import uz.hamkorbank.commhub.application.port.out.ProviderDeliveryStats;
import uz.hamkorbank.commhub.application.port.out.ProviderStatsPort;
import uz.hamkorbank.commhub.application.port.out.provider.ProviderProbePort;
import uz.hamkorbank.commhub.domain.model.Provider;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.ProviderHealthStatus;

/** Passive health detection with automatic failover and failback (FR-6.3, PR-02). */
class ProviderHealthServiceTest {

    private ProviderConfigRepository providers;
    private ProviderStatsPort stats;
    private ProviderHealthService service;
    private ProviderProbePort probe;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        providers = mock(ProviderConfigRepository.class);
        stats = mock(ProviderStatsPort.class);
        probe = mock(ProviderProbePort.class);
        ClockPort clock = mock(ClockPort.class);
        when(clock.now()).thenReturn(NOW);
        ObjectProvider<ProviderProbePort> probes = mock(ObjectProvider.class);
        when(probes.stream()).thenAnswer(invocation -> Stream.of(probe));
        when(probe.supports(any())).thenReturn(false);
        service = new ProviderHealthService(providers, stats, probes, ProviderHealthPolicy.defaults(), clock);
    }

    @Test
    @DisplayName("FR-6.3: a provider over the error threshold goes DOWN and leaves routing")
    void failsOverOnErrorRate() {
        // Arrange
        Provider provider = smsProvider("PLAYMOBILE");
        when(providers.findAllProviders()).thenReturn(List.of(provider));
        when(stats.statsSince(any(), any()))
                .thenReturn(List.of(new ProviderDeliveryStats(provider.id(), 40L, 30L, 5L, 900.0d)));

        // Act
        ProviderHealthResult result = service.check(CheckProviderHealthCommand.allChannels());

        // Assert
        assertThat(result.checked()).isEqualTo(1);
        assertThat(result.transitions()).singleElement().satisfies(transition -> {
            assertThat(transition.to()).isEqualTo(ProviderHealthStatus.DOWN);
            assertThat(transition.isFailover()).isTrue();
        });
        verify(providers).updateHealth(eq(provider.id()), eq(ProviderHealthStatus.DOWN), any(), eq(NOW));
    }

    @Test
    @DisplayName("FR-6.3: a silent provider goes DOWN on timeouts alone, below the error threshold")
    void failsOverOnTimeouts() {
        // Arrange
        Provider provider = smsProvider("SMSGATE");
        when(providers.findAllProviders()).thenReturn(List.of(provider));
        when(stats.statsSince(any(), any()))
                .thenReturn(List.of(new ProviderDeliveryStats(provider.id(), 100L, 35L, 35L, 10_000.0d)));

        // Act
        ProviderHealthResult result = service.check(CheckProviderHealthCommand.allChannels());

        // Assert
        assertThat(result.transitions()).singleElement().satisfies(transition -> assertThat(transition.to())
                .isEqualTo(ProviderHealthStatus.DOWN));
    }

    @Test
    @DisplayName("FR-6.3: a clean window brings a recovered provider back into routing")
    void failsBackOnCleanTraffic() {
        // Arrange
        Provider provider = smsProvider("PLAYMOBILE");
        provider.markHealth(ProviderHealthStatus.DOWN, NOW.minus(Duration.ofMinutes(10)));
        when(providers.findAllProviders()).thenReturn(List.of(provider));
        when(stats.statsSince(any(), any()))
                .thenReturn(List.of(new ProviderDeliveryStats(provider.id(), 5L, 0L, 0L, 120.0d)));

        // Act
        ProviderHealthResult result = service.check(CheckProviderHealthCommand.allChannels());

        // Assert
        assertThat(result.transitions()).singleElement().satisfies(transition -> {
            assertThat(transition.to()).isEqualTo(ProviderHealthStatus.UP);
            assertThat(transition.isFailback()).isTrue();
        });
    }

    @Test
    @DisplayName("FR-6.3: a DOWN provider with no traffic is put on probation so it can recover at all")
    void probationEndsTheSilence() {
        // Arrange
        Provider provider = smsProvider("PLAYMOBILE");
        provider.markHealth(ProviderHealthStatus.DOWN, NOW.minus(Duration.ofMinutes(5)));
        when(providers.findAllProviders()).thenReturn(List.of(provider));
        when(stats.statsSince(any(), any())).thenReturn(List.of());

        // Act
        ProviderHealthResult result = service.check(CheckProviderHealthCommand.allChannels());

        // Assert
        assertThat(result.transitions()).singleElement().satisfies(transition -> {
            assertThat(transition.to()).isEqualTo(ProviderHealthStatus.UNKNOWN);
            assertThat(transition.isFailback()).isTrue();
        });
    }

    @Test
    @DisplayName("a failing probe takes the provider down whatever the recent traffic says (PR-02)")
    void probeOverridesTheFigures() {
        // Arrange
        Provider provider = smsProvider("PLAYMOBILE");
        when(providers.findProviders(Channel.SMS)).thenReturn(List.of(provider));
        when(stats.statsSince(any(), any()))
                .thenReturn(List.of(new ProviderDeliveryStats(provider.id(), 50L, 0L, 0L, 100.0d)));
        when(probe.supports(provider.ref())).thenReturn(true);
        when(probe.probe(provider.ref())).thenReturn(ProviderProbePort.ProbeResult.unhealthy("connect refused"));

        // Act
        ProviderHealthResult result = service.check(new CheckProviderHealthCommand(Channel.SMS));

        // Assert
        assertThat(result.transitions()).singleElement().satisfies(transition -> assertThat(transition.to())
                .isEqualTo(ProviderHealthStatus.DOWN));
    }

    @Test
    @DisplayName("disabled providers are not measured: their health is not a fact about anything")
    void skipsDisabledProviders() {
        // Arrange
        Provider provider = smsProvider("SMSGATE");
        provider.disable();
        when(providers.findAllProviders()).thenReturn(List.of(provider));

        // Act
        ProviderHealthResult result = service.check(CheckProviderHealthCommand.allChannels());

        // Assert
        assertThat(result.checked()).isZero();
        assertThat(result.hasTransitions()).isFalse();
        verify(providers, never()).updateHealth(any(), any(), any(), any());
    }
}
