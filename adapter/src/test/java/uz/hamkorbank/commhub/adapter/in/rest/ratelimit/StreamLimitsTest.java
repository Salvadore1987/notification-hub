package uz.hamkorbank.commhub.adapter.in.rest.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.hamkorbank.commhub.adapter.in.rest.ratelimit.RateLimitProperties.StreamLimit;
import uz.hamkorbank.commhub.application.port.out.StreamRepository;
import uz.hamkorbank.commhub.domain.model.RateLimit;
import uz.hamkorbank.commhub.domain.model.Stream;
import uz.hamkorbank.commhub.domain.model.type.IntegrationType;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;

/** Per-stream request limits come from the registry, with the deployment defaults behind them (IR-02). */
class StreamLimitsTest {

    private static final StreamId STREAM = StreamId.of("ibank-retail");

    private StreamRepository streams;

    @BeforeEach
    void setUp() {
        streams = mock(StreamRepository.class);
    }

    @Test
    @DisplayName("IR-02, AD-07: the limit stored on the stream wins over the configured default")
    void registryLimitWins() {
        // Arrange
        Stream stream = stream();
        stream.updateRateLimit(new RateLimit(500, 20_000, 0));
        when(streams.findById(STREAM)).thenReturn(Optional.of(stream));
        StreamLimits limits = new StreamLimits(streams, RateLimitProperties.defaults());

        // Act
        StreamLimit limit = limits.of(STREAM.value());

        // Assert
        assertThat(limit.permitsPerSecond()).isEqualTo(500.0d);
        assertThat(limit.burst()).isEqualTo(20_000);
    }

    @Test
    @DisplayName("a rate without a per-minute ceiling gets two seconds of it as the burst")
    void burstDefaultsToTwoSecondsOfRate() {
        // Arrange
        Stream stream = stream();
        stream.updateRateLimit(RateLimit.ofTps(50));
        when(streams.findById(STREAM)).thenReturn(Optional.of(stream));
        StreamLimits limits = new StreamLimits(streams, RateLimitProperties.defaults());

        // Act + Assert
        assertThat(limits.of(STREAM.value()).burst()).isEqualTo(100);
    }

    @Test
    @DisplayName("a stream without a limit of its own falls back to the configured one")
    void fallsBackToConfiguration() {
        // Arrange
        when(streams.findById(STREAM)).thenReturn(Optional.of(stream()));
        RateLimitProperties properties =
                new RateLimitProperties(true, 200.0, 400, Map.of(STREAM.value(), new StreamLimit(50.0, 100)), null);
        StreamLimits limits = new StreamLimits(streams, properties);

        // Act + Assert
        assertThat(limits.of(STREAM.value()).permitsPerSecond()).isEqualTo(50.0d);
    }

    @Test
    @DisplayName("an unreadable registry must not become the reason a request is refused")
    void registryFailureFallsBack() {
        // Arrange
        when(streams.findById(any())).thenThrow(new IllegalStateException("database is down"));
        StreamLimits limits = new StreamLimits(streams, RateLimitProperties.defaults());

        // Act + Assert
        assertThat(limits.of(STREAM.value()).permitsPerSecond())
                .isEqualTo(RateLimitProperties.DEFAULT_PERMITS_PER_SECOND);
    }

    @Test
    @DisplayName("the registry is read once per window: the limiter runs on every accepted request")
    void limitIsCached() {
        // Arrange
        when(streams.findById(STREAM)).thenReturn(Optional.of(stream()));
        StreamLimits limits = new StreamLimits(streams, RateLimitProperties.defaults());

        // Act
        limits.of(STREAM.value());
        limits.of(STREAM.value());
        limits.of(STREAM.value());

        // Assert
        verify(streams, times(1)).findById(STREAM);
    }

    @Test
    @DisplayName("without a registry the limits are the configured ones, as before Phase 8")
    void configurationOnlyNeedsNoRegistry() {
        // Act + Assert
        assertThat(StreamLimits.configurationOnly(RateLimitProperties.defaults())
                        .of(STREAM.value())
                        .burst())
                .isEqualTo(RateLimitProperties.DEFAULT_BURST);
    }

    private static Stream stream() {
        return Stream.register(STREAM, "iBank retail", IntegrationType.REST, Stream.Defaults.none());
    }
}
