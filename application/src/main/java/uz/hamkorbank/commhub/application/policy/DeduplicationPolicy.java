package uz.hamkorbank.commhub.application.policy;

import java.time.Duration;
import java.time.Instant;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Idempotency window of submissions (FR-1.5).
 *
 * @param window how long a {@code dedupKey} stays claimed; 24 h by default
 */
public record DeduplicationPolicy(Duration window) {

    public static final Duration DEFAULT_WINDOW = Duration.ofHours(24);

    public DeduplicationPolicy {
        Guard.notNull(window, "DeduplicationPolicy.window");
        Guard.isTrue(!window.isNegative() && !window.isZero(), "DeduplicationPolicy.window must be positive");
    }

    public static DeduplicationPolicy defaults() {
        return new DeduplicationPolicy(DEFAULT_WINDOW);
    }

    /** Earliest instant a submission still counts as a repetition. */
    public Instant windowStart(Instant now) {
        Guard.notNull(now, "now");
        return now.minus(window);
    }

    /** Instant at which the claim on a key may be released. */
    public Instant expiresAt(Instant now) {
        Guard.notNull(now, "now");
        return now.plus(window);
    }
}
