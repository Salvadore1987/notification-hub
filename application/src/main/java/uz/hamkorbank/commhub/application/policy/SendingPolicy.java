package uz.hamkorbank.commhub.application.policy;

import java.time.Duration;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Retry and failover budget of the sending saga (PR-01, FR-2.2, FR-6.3).
 *
 * <p>The backoff itself is applied by the dispatcher and by the Resilience4j configuration of the
 * adapters; the saga only decides whether another attempt is still allowed and on which provider.
 *
 * @param maxAttemptsPerProvider attempts on one provider before failing over to the next one
 * @param maxTotalAttempts attempts across all providers before the message goes to the DLQ (FR-3.3)
 * @param initialBackoff delay before the first retry; grows by {@link #backoffMultiplier()}
 * @param maxBackoff upper bound of the delay
 */
public record SendingPolicy(
        int maxAttemptsPerProvider,
        int maxTotalAttempts,
        Duration initialBackoff,
        double backoffMultiplier,
        Duration maxBackoff) {

    public SendingPolicy {
        Guard.positive(maxAttemptsPerProvider, "SendingPolicy.maxAttemptsPerProvider");
        Guard.positive(maxTotalAttempts, "SendingPolicy.maxTotalAttempts");
        Guard.notNull(initialBackoff, "SendingPolicy.initialBackoff");
        Guard.notNull(maxBackoff, "SendingPolicy.maxBackoff");
        Guard.isTrue(
                maxTotalAttempts >= maxAttemptsPerProvider,
                "SendingPolicy.maxTotalAttempts must not be lower than maxAttemptsPerProvider");
        Guard.isTrue(backoffMultiplier >= 1.0d, "SendingPolicy.backoffMultiplier must be at least 1.0");
    }

    /** Two attempts per provider, five in total, 2 s → 30 s exponential backoff. */
    public static SendingPolicy defaults() {
        return new SendingPolicy(2, 5, Duration.ofSeconds(2), 2.0d, Duration.ofSeconds(30));
    }

    /** Whether one more attempt is allowed at all (PR-01). */
    public boolean allowsAttempt(int attemptsMade) {
        return attemptsMade < maxTotalAttempts;
    }

    /** Whether the current provider still has attempts left before a failover (FR-6.3). */
    public boolean allowsRetryOnSameProvider(int attemptsOnProvider) {
        return attemptsOnProvider < maxAttemptsPerProvider;
    }

    /** Delay before the retry numbered {@code attemptsMade + 1}, capped by {@link #maxBackoff()}. */
    public Duration backoffFor(int attemptsMade) {
        Guard.notNegative(attemptsMade, "attemptsMade");
        double factor = Math.pow(backoffMultiplier, Math.max(0, attemptsMade - 1));
        Duration delay = Duration.ofMillis(Math.round(initialBackoff.toMillis() * factor));
        return delay.compareTo(maxBackoff) > 0 ? maxBackoff : delay;
    }
}
