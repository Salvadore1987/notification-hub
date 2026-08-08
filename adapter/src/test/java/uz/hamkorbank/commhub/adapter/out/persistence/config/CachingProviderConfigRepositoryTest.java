package uz.hamkorbank.commhub.adapter.out.persistence.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.hamkorbank.commhub.domain.model.Provider;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.vo.AdapterType;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.model.vo.ProviderId;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;
import uz.hamkorbank.commhub.domain.service.RoutingConfiguration;

/** The routing snapshot is read once per window, and a local change is seen at once (AD-07, NF-07). */
class CachingProviderConfigRepositoryTest {

    private static final StreamId STREAM = StreamId.of("mobile-app");
    private static final StreamId OTHER_STREAM = StreamId.of("core-banking");

    private ProviderConfigPersistenceAdapter delegate;

    @BeforeEach
    void setUp() {
        delegate = mock(ProviderConfigPersistenceAdapter.class);
        when(delegate.routingConfiguration(any())).thenReturn(RoutingConfiguration.of(Map.of(), List.of(), List.of()));
    }

    @Test
    @DisplayName("AD-07: the configuration is read once and served from memory afterwards")
    void readsTheConfigurationOncePerWindow() {
        // Arrange
        CachingProviderConfigRepository cache = cacheWith(Duration.ofSeconds(30));

        // Act
        cache.routingConfiguration(STREAM);
        cache.routingConfiguration(STREAM);
        cache.routingConfiguration(STREAM);

        // Assert
        verify(delegate, times(1)).routingConfiguration(STREAM);
    }

    @Test
    @DisplayName("every stream keeps its own snapshot: the defaults inside differ (FR-2.4, TC-02)")
    void cachesPerStream() {
        // Arrange
        CachingProviderConfigRepository cache = cacheWith(Duration.ofSeconds(30));

        // Act
        cache.routingConfiguration(STREAM);
        cache.routingConfiguration(OTHER_STREAM);
        cache.routingConfiguration(STREAM);
        cache.routingConfiguration(OTHER_STREAM);

        // Assert
        verify(delegate, times(1)).routingConfiguration(STREAM);
        verify(delegate, times(1)).routingConfiguration(OTHER_STREAM);
    }

    @Test
    @DisplayName("AD-07: a change made on this instance is visible to the very next message")
    void aLocalWriteInvalidatesTheSnapshot() {
        // Arrange
        CachingProviderConfigRepository cache = cacheWith(Duration.ofSeconds(30));
        cache.routingConfiguration(STREAM);

        // Act
        cache.save(provider());
        cache.routingConfiguration(STREAM);

        // Assert
        verify(delegate, times(2)).routingConfiguration(STREAM);
    }

    @Test
    @DisplayName("NF-07: an expired snapshot is reloaded")
    void expiredSnapshotIsReloaded() throws InterruptedException {
        // Arrange
        CachingProviderConfigRepository cache = cacheWith(Duration.ofMillis(1));
        cache.routingConfiguration(STREAM);

        // Act
        Thread.sleep(5L);
        cache.routingConfiguration(STREAM);

        // Assert
        verify(delegate, times(2)).routingConfiguration(STREAM);
    }

    @Test
    @DisplayName("the scheduled refresh reloads what is cached, and a failure keeps the old snapshot")
    void refreshKeepsServingOnFailure() {
        // Arrange
        CachingProviderConfigRepository cache = cacheWith(Duration.ofSeconds(30));
        cache.routingConfiguration(STREAM);
        when(delegate.routingConfiguration(STREAM)).thenThrow(new IllegalStateException("database is down"));

        // Act
        cache.refresh();
        RoutingConfiguration served = cache.routingConfiguration(STREAM);

        // Assert
        assertThat(served).isNotNull();
        verify(delegate, times(2)).routingConfiguration(STREAM);
    }

    @Test
    @DisplayName("with the cache off every decision reads the database, which is what tests want")
    void disabledCacheAlwaysReads() {
        // Arrange
        CachingProviderConfigRepository cache = new CachingProviderConfigRepository(
                delegate, new ConfigurationCacheProperties(false, Duration.ofSeconds(30)));

        // Act
        cache.routingConfiguration(STREAM);
        cache.routingConfiguration(STREAM);

        // Assert
        verify(delegate, times(2)).routingConfiguration(STREAM);
    }

    @Test
    @DisplayName("NF-07 is a ceiling: a refresh interval above 30 s is refused at startup")
    void refreshIntervalIsCapped() {
        // Act + Assert
        assertThat(ConfigurationCacheProperties.defaults().refreshInterval())
                .isEqualTo(ConfigurationCacheProperties.DEFAULT_REFRESH_INTERVAL);
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> new ConfigurationCacheProperties(true, Duration.ofMinutes(5)))
                .withMessageContaining("NF-07");
    }

    private CachingProviderConfigRepository cacheWith(Duration refreshInterval) {
        return new CachingProviderConfigRepository(delegate, new ConfigurationCacheProperties(true, refreshInterval));
    }

    private static Provider provider() {
        return Provider.register(
                ProviderId.newId(),
                ProviderCode.of("PLAYMOBILE"),
                Channel.SMS,
                AdapterType.of("playmobile-http"),
                Provider.Settings.defaults());
    }
}
