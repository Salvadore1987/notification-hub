package uz.hamkorbank.commhub.adapter.in.rest.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.hamkorbank.commhub.adapter.in.rest.ratelimit.RateLimitProperties.StreamLimit;

/** Per-stream token bucket of the synchronous API (IR-02). */
class StreamRateLimiterTest {

    private static final long SECOND = 1_000_000_000L;

    @Test
    @DisplayName("IR-02: a stream may spend its burst and is then refused")
    void refusesAStreamOverItsBurst() {
        // Arrange
        StreamRateLimiter limiter = limiter(10.0, 3);

        // Act
        limiter.check("ibank-retail", 0L);
        limiter.check("ibank-retail", 0L);
        limiter.check("ibank-retail", 0L);

        // Assert
        assertThatThrownBy(() -> limiter.check("ibank-retail", 0L))
                .isInstanceOf(RateLimitExceededException.class)
                .satisfies(thrown -> assertThat(((RateLimitExceededException) thrown).streamId())
                        .isEqualTo("ibank-retail"));
    }

    @Test
    @DisplayName("IR-02: the bucket refills at the configured rate")
    void refillsOverTime() {
        // Arrange
        StreamRateLimiter limiter = limiter(10.0, 1);
        limiter.check("ibank-retail", 0L);

        // Act + Assert: 10/s means one permit every 100 ms
        assertThatThrownBy(() -> limiter.check("ibank-retail", SECOND / 20))
                .isInstanceOf(RateLimitExceededException.class);
        assertThatCode(() -> limiter.check("ibank-retail", SECOND / 10)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("TC-01: one stream running hot does not affect another")
    void isolatesStreamsFromEachOther() {
        // Arrange
        StreamRateLimiter limiter = limiter(1.0, 1);
        limiter.check("bulk-marketing", 0L);

        // Act + Assert
        assertThatThrownBy(() -> limiter.check("bulk-marketing", 0L)).isInstanceOf(RateLimitExceededException.class);
        assertThatCode(() -> limiter.check("otp-processing", 0L)).doesNotThrowAnyException();
        assertThat(limiter.trackedStreams()).isEqualTo(2);
    }

    @Test
    @DisplayName("A stream with its own limit does not take the default")
    void appliesPerStreamOverrides() {
        // Arrange
        RateLimitProperties properties =
                new RateLimitProperties(true, 1.0, 1, Map.of("otp-processing", new StreamLimit(100.0, 50)), null);
        StreamRateLimiter limiter = limiterOf(properties);

        // Act
        for (int permit = 0; permit < 50; permit++) {
            limiter.check("otp-processing", 0L);
        }

        // Assert
        assertThatThrownBy(() -> limiter.check("otp-processing", 0L)).isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    @DisplayName("Disabled means disabled: nothing is counted")
    void doesNothingWhenDisabled() {
        // Arrange
        StreamRateLimiter limiter = limiterOf(new RateLimitProperties(false, 1.0, 1, Map.of(), null));

        // Act + Assert
        assertThatCode(() -> {
                    for (int permit = 0; permit < 100; permit++) {
                        limiter.check("ibank-retail", 0L);
                    }
                })
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Retry-After is never 0, which a client would read as \"now\"")
    void roundsRetryAfterUpToAWholeSecond() {
        // Arrange
        RateLimitExceededException exceeded = new RateLimitExceededException("s", Duration.ofMillis(120));

        // Act + Assert
        assertThat(exceeded.retryAfterSeconds()).isEqualTo(1L);
    }

    private static StreamRateLimiter limiter(double permitsPerSecond, int burst) {
        return limiterOf(new RateLimitProperties(true, permitsPerSecond, burst, Map.of(), null));
    }

    private static StreamRateLimiter limiterOf(RateLimitProperties properties) {
        return new StreamRateLimiter(properties, StreamLimits.configurationOnly(properties));
    }
}
