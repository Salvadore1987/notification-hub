package uz.hamkorbank.commhub.adapter.out.provider.support;

import java.time.Duration;

/**
 * Retry and circuit breaker of one provider (PR-01, FR-6.3, §9.5).
 *
 * <p>Two layers of retry exist and they answer different questions. This one is inside a single
 * delivery attempt and covers a blip on the wire — a reset connection, one 503 — so it is short and
 * measured in hundreds of milliseconds. The saga's {@code SendingPolicy} is the outer one: it decides
 * whether the message gets another attempt at all and on which provider, and it is measured in
 * seconds. Setting {@link #maxAttempts()} to 1 disables the inner layer, which is the right default for
 * a provider whose API is not idempotent.
 *
 * <p>The breaker is per provider: an account lock at Playmobile (§18.1 code 102) must stop traffic to
 * Playmobile and to nothing else, so the fallback chain has somewhere to go (FR-2.2).
 *
 * @param maxAttempts calls per delivery attempt including the first; 1 means "do not retry inside the
 *     adapter"
 * @param initialBackoff delay before the second call, multiplied by {@link #backoffMultiplier()}
 * @param backoffMultiplier growth of the delay between calls
 * @param jitter randomisation of each delay, 0…1 — spreads the retries of a batch that all failed at
 *     the same moment instead of letting them hit the recovering provider together (PR-01)
 * @param failureRateThreshold percentage of failed calls in the window that opens the breaker
 * @param slidingWindowSize calls the failure rate is computed over
 * @param minimumCalls calls needed before the rate is evaluated at all; without it, one failed call at
 *     three in the morning opens the breaker
 * @param openDuration how long the breaker stays open before it lets a probe through (failback, FR-6.3)
 */
public record ProviderResilienceProperties(
        Integer maxAttempts,
        Duration initialBackoff,
        Double backoffMultiplier,
        Double jitter,
        Float failureRateThreshold,
        Integer slidingWindowSize,
        Integer minimumCalls,
        Duration openDuration) {

    public static final int DEFAULT_MAX_ATTEMPTS = 2;
    public static final Duration DEFAULT_INITIAL_BACKOFF = Duration.ofMillis(200);
    public static final double DEFAULT_BACKOFF_MULTIPLIER = 2.0d;
    public static final double DEFAULT_JITTER = 0.3d;
    public static final float DEFAULT_FAILURE_RATE_THRESHOLD = 50.0f;
    public static final int DEFAULT_SLIDING_WINDOW_SIZE = 20;
    public static final int DEFAULT_MINIMUM_CALLS = 10;
    public static final Duration DEFAULT_OPEN_DURATION = Duration.ofSeconds(30);

    public ProviderResilienceProperties {
        maxAttempts = maxAttempts == null || maxAttempts < 1 ? DEFAULT_MAX_ATTEMPTS : maxAttempts;
        initialBackoff = initialBackoff == null || initialBackoff.isNegative() || initialBackoff.isZero()
                ? DEFAULT_INITIAL_BACKOFF
                : initialBackoff;
        backoffMultiplier =
                backoffMultiplier == null || backoffMultiplier < 1.0d ? DEFAULT_BACKOFF_MULTIPLIER : backoffMultiplier;
        jitter = jitter == null || jitter < 0.0d || jitter >= 1.0d ? DEFAULT_JITTER : jitter;
        failureRateThreshold =
                failureRateThreshold == null || failureRateThreshold <= 0.0f || failureRateThreshold > 100.0f
                        ? DEFAULT_FAILURE_RATE_THRESHOLD
                        : failureRateThreshold;
        slidingWindowSize =
                slidingWindowSize == null || slidingWindowSize < 1 ? DEFAULT_SLIDING_WINDOW_SIZE : slidingWindowSize;
        minimumCalls = minimumCalls == null || minimumCalls < 1 ? DEFAULT_MINIMUM_CALLS : minimumCalls;
        openDuration = openDuration == null || openDuration.isNegative() || openDuration.isZero()
                ? DEFAULT_OPEN_DURATION
                : openDuration;
    }

    public static ProviderResilienceProperties defaults() {
        return new ProviderResilienceProperties(null, null, null, null, null, null, null, null);
    }

    /** Settings of a provider whose send API is not idempotent: no retry inside the attempt. */
    public static ProviderResilienceProperties withoutInnerRetry() {
        return new ProviderResilienceProperties(1, null, null, null, null, null, null, null);
    }

    public boolean retriesInsideAttempt() {
        return maxAttempts > 1;
    }
}
