package uz.hamkorbank.commhub.adapter.out.provider.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.hamkorbank.commhub.adapter.out.persistence.config.ConfigurationCacheProperties;
import uz.hamkorbank.commhub.adapter.out.provider.playmobile.PlaymobileProperties;
import uz.hamkorbank.commhub.adapter.out.provider.smsgate.SmsGateProperties;
import uz.hamkorbank.commhub.application.port.out.ProviderConfigRepository;
import uz.hamkorbank.commhub.domain.model.Provider;
import uz.hamkorbank.commhub.domain.model.RateLimit;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.Priority;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;
import uz.hamkorbank.commhub.domain.model.vo.AdapterType;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.model.vo.ProviderId;

/** Limits and endpoint settings read from the provider profile at runtime (FR-2.5, FR-2.7, AD-07). */
class ProviderRuntimeSettingsTest {

    private ProviderConfigRepository configuration;
    private ProviderRuntimeSettings settings;

    @BeforeEach
    void setUp() {
        configuration = mock(ProviderConfigRepository.class);
        settings = new ProviderRuntimeSettings(configuration, ConfigurationCacheProperties.defaults());
    }

    @Test
    @DisplayName("FR-2.5: the limit stored on the provider row wins over the deployed default")
    void databaseLimitWins() {
        // Arrange
        Provider provider = provider();
        provider.updateRateLimit(new RateLimit(20, 600, 30));
        when(configuration.findProviderByCode(ProviderCode.of("SMSGATE"))).thenReturn(Optional.of(provider));
        when(configuration.endpointConfig(provider.id())).thenReturn(Map.of());

        // Act
        RateLimit resolved = settings.rateLimitOf("SMSGATE", new RateLimit(0, 0, 45));

        // Assert
        assertThat(resolved.tps()).isEqualTo(20);
        assertThat(resolved.perRecipientPerHour()).isEqualTo(30);
    }

    @Test
    @DisplayName("a provider row with no limit of its own keeps the deployed default, not 'unlimited'")
    void unconfiguredLimitFallsBack() {
        // Arrange
        Provider provider = provider();
        when(configuration.findProviderByCode(ProviderCode.of("SMSGATE"))).thenReturn(Optional.of(provider));
        when(configuration.endpointConfig(provider.id())).thenReturn(Map.of());

        // Act
        RateLimit resolved = settings.rateLimitOf("SMSGATE", new RateLimit(0, 0, 45));

        // Assert
        assertThat(resolved.perRecipientPerHour()).isEqualTo(45);
    }

    @Test
    @DisplayName("an unregistered provider — or an unreachable database — leaves the adapter on its defaults")
    void missingProfileNeverStopsSending() {
        // Arrange
        when(configuration.findProviderByCode(any())).thenThrow(new IllegalStateException("database is down"));

        // Act
        RateLimit resolved = settings.rateLimitOf("SMSGATE", new RateLimit(10, 0, 45));

        // Assert
        assertThat(resolved.tps()).isEqualTo(10);
        assertThat(settings.endpointConfigOf("SMSGATE")).isEmpty();
    }

    @Test
    @DisplayName("AD-07: the profile is read once per refresh window, not per message")
    void profileIsCached() {
        // Arrange
        Provider provider = provider();
        when(configuration.findProviderByCode(ProviderCode.of("SMSGATE"))).thenReturn(Optional.of(provider));
        when(configuration.endpointConfig(provider.id())).thenReturn(Map.of("sender", "HAMKORBANK"));

        // Act
        settings.endpointConfigOf("SMSGATE");
        settings.endpointConfigOf("SMSGATE");
        settings.rateLimitOf("SMSGATE", RateLimit.unlimited());

        // Assert
        verify(configuration, times(1)).findProviderByCode(ProviderCode.of("SMSGATE"));
        settings.invalidate();
        settings.endpointConfigOf("SMSGATE");
        verify(configuration, times(2)).findProviderByCode(ProviderCode.of("SMSGATE"));
    }

    @Test
    @DisplayName("§9.1: endpoint_config overrides the Playmobile alpha-name, prefix, TTL and priorities")
    void playmobileOverlay() {
        // Arrange
        PlaymobileProperties.Sending configured =
                new PlaymobileProperties.Sending("3700", "HB", Duration.ofMinutes(30), null);

        // Act
        PlaymobileProperties.Sending overlaid = configured.overlay(Map.of(
                "originator", "HAMKOR",
                "message-id-prefix", "HK",
                "default-ttl", "PT10M",
                "priority.notification", "normal",
                "priority.transactional", "not-a-priority"));

        // Assert
        assertThat(overlaid.originator()).isEqualTo("HAMKOR");
        assertThat(overlaid.organisationPrefix()).isEqualTo("HK");
        assertThat(overlaid.defaultTtl()).isEqualTo(Duration.ofMinutes(10));
        assertThat(overlaid.priorityOf(TrafficClass.NOTIFICATION, Priority.LOW))
                .isEqualTo(PlaymobileProperties.Sending.NORMAL);
        assertThat(overlaid.priorityOf(TrafficClass.TRANSACTIONAL, Priority.NORMAL))
                .isEqualTo(PlaymobileProperties.Sending.NORMAL);
        assertThat(configured.overlay(Map.of()).originator()).isEqualTo("3700");
    }

    @Test
    @DisplayName("an unparsable TTL leaves the configured value in place instead of dropping the field")
    void playmobileOverlayIgnoresGarbage() {
        // Arrange
        PlaymobileProperties.Sending configured =
                new PlaymobileProperties.Sending("3700", "HB", Duration.ofMinutes(30), null);

        // Act
        PlaymobileProperties.Sending overlaid = configured.overlay(Map.of("default-ttl", "half an hour"));

        // Assert
        assertThat(overlaid.defaultTtl()).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    @DisplayName("§9.2: endpoint_config overrides the SMS Gate sender and weights, within 0–10")
    void smsGateOverlay() {
        // Arrange
        SmsGateProperties.Sending configured = new SmsGateProperties.Sending("HAMKOR", null);

        // Act
        SmsGateProperties.Sending overlaid = configured.overlay(Map.of(
                "sender", "HAMKORBANK",
                "weight.notification", "1",
                "weight.transactional", "99"));

        // Assert
        assertThat(overlaid.sender()).isEqualTo("HAMKORBANK");
        assertThat(overlaid.weightOf(TrafficClass.NOTIFICATION)).isEqualTo(1);
        assertThat(overlaid.weightOf(TrafficClass.TRANSACTIONAL)).isEqualTo(7);
        assertThat(configured.overlay(Map.of()).sender()).isEqualTo("HAMKOR");
    }

    private static Provider provider() {
        return Provider.register(
                ProviderId.newId(),
                ProviderCode.of("SMSGATE"),
                Channel.SMS,
                AdapterType.of("smsgate-http"),
                Provider.Settings.defaults());
    }
}
