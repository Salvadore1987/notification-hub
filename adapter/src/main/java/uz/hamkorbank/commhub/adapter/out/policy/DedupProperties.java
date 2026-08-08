package uz.hamkorbank.commhub.adapter.out.policy;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import uz.hamkorbank.commhub.application.policy.DeduplicationPolicy;

/**
 * Idempotency window of submissions (FR-1.5).
 *
 * <p>The window is also what {@code RetentionSweepScheduler} keeps {@code dedup_registry} the size of,
 * so widening it widens a table that is written on every accepted message (DB-03).
 *
 * @param window how long a {@code dedupKey} stays claimed; 24 h when unset
 */
@ConfigurationProperties("commhub.dedup")
public record DedupProperties(Duration window) {

    public DeduplicationPolicy toPolicy() {
        return window == null ? DeduplicationPolicy.defaults() : new DeduplicationPolicy(window);
    }
}
