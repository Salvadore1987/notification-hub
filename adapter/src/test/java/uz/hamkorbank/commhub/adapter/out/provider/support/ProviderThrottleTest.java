package uz.hamkorbank.commhub.adapter.out.provider.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.hamkorbank.commhub.domain.model.RateLimit;

/** Provider throughput and anti-spam ceilings (FR-2.5, §18.2). */
class ProviderThrottleTest {

    private static final String PROVIDER = "SMSGATE";

    private static final String RECIPIENT = "998901234567";

    private ProviderThrottle throttle;

    @BeforeEach
    void setUp() {
        throttle = new ProviderThrottle();
    }

    @Test
    @DisplayName("FR-2.5: a provider with no configured limits never holds a message back")
    void unlimitedProviderPassesEverything() {
        // Act + Assert
        for (int i = 0; i < 1_000; i++) {
            assertThat(throttle.acquire(PROVIDER, RateLimit.unlimited(), RECIPIENT))
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("§18.2: the 45th message to one number passes and the 46th is held back")
    void enforcesPerRecipientHourlyCeiling() {
        // Arrange
        RateLimit limit = new RateLimit(0, 0, 45);

        // Act
        for (int i = 0; i < 45; i++) {
            assertThat(throttle.acquire(PROVIDER, limit, RECIPIENT)).isEmpty();
        }
        Optional<String> refusal = throttle.acquire(PROVIDER, limit, RECIPIENT);

        // Assert
        assertThat(refusal).isPresent();
        assertThat(refusal.get()).contains("45").contains("per hour");
    }

    @Test
    @DisplayName("§18.2: the ceiling is per number — a second recipient still gets through")
    void perRecipientCeilingDoesNotLeakBetweenNumbers() {
        // Arrange
        RateLimit limit = new RateLimit(0, 0, 2);
        throttle.acquire(PROVIDER, limit, RECIPIENT);
        throttle.acquire(PROVIDER, limit, RECIPIENT);

        // Act
        Optional<String> exhausted = throttle.acquire(PROVIDER, limit, RECIPIENT);
        Optional<String> other = throttle.acquire(PROVIDER, limit, "998907654321");

        // Assert
        assertThat(exhausted).isPresent();
        assertThat(other).isEmpty();
    }

    @Test
    @DisplayName("FR-2.5: the per-minute ceiling bounds the provider as a whole")
    void enforcesPerMinuteCeiling() {
        // Arrange
        RateLimit limit = new RateLimit(0, 3, 0);

        // Act
        for (int i = 0; i < 3; i++) {
            assertThat(throttle.acquire(PROVIDER, limit, RECIPIENT)).isEmpty();
        }
        Optional<String> refusal = throttle.acquire(PROVIDER, limit, "998907654321");

        // Assert
        assertThat(refusal).isPresent();
        assertThat(refusal.get()).contains("per minute");
    }

    @Test
    @DisplayName("FR-2.5: a burst beyond the configured TPS is held back")
    void enforcesTpsLimit() {
        // Arrange: a bucket one second deep, drained back to back leaves nothing to refill it.
        RateLimit limit = RateLimit.ofTps(5);

        // Act
        int allowed = 0;
        for (int i = 0; i < 50; i++) {
            if (throttle.acquire(PROVIDER, limit, null).isEmpty()) {
                allowed++;
            }
        }

        // Assert
        assertThat(allowed).isLessThan(50).isGreaterThanOrEqualTo(5);
    }

    @Test
    @DisplayName("AD-07: resetting the counters lets a configuration change take effect at once")
    void resetClearsCounters() {
        // Arrange
        RateLimit limit = new RateLimit(0, 0, 1);
        throttle.acquire(PROVIDER, limit, RECIPIENT);
        assertThat(throttle.acquire(PROVIDER, limit, RECIPIENT)).isPresent();

        // Act
        throttle.reset();

        // Assert
        assertThat(throttle.acquire(PROVIDER, limit, RECIPIENT)).isEmpty();
    }
}
