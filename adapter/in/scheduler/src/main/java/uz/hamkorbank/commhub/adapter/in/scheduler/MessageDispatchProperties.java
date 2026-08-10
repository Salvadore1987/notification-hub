package uz.hamkorbank.commhub.adapter.in.scheduler;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Pace of the sending dispatcher, per traffic class (AD-04, TC-01, ADR-0039).
 *
 * <p>Each class gets its own numbers because that is what makes the isolation of TC-01 structural: a
 * bulk campaign cannot take turns away from OTP if it is drained by a different scheduler with its own
 * semaphore. OTP polls often and takes small pages; notifications poll rarely and take large ones.
 *
 * @param enabled whether this instance dispatches; every instance does by default — rows are claimed
 *     with {@code SKIP LOCKED}, so instances share the queue instead of duplicating it
 * @param lease how long a claimed message stays claimed. Long enough to cover the slowest provider call
 *     plus both transactions, short enough that a killed pod does not strand messages for minutes
 * @param owner instance identity written on claimed rows; empty falls back to the host name
 */
@ConfigurationProperties("commhub.dispatch")
public record MessageDispatchProperties(
        Boolean enabled, Duration lease, String owner, Pace criticalOtp, Pace transactional, Pace notification) {

    public static final Duration DEFAULT_LEASE = Duration.ofMinutes(2);

    public MessageDispatchProperties {
        enabled = enabled == null || enabled;
        lease = lease == null ? DEFAULT_LEASE : lease;
        criticalOtp = criticalOtp == null ? Pace.criticalOtpDefaults() : criticalOtp;
        transactional = transactional == null ? Pace.transactionalDefaults() : transactional;
        notification = notification == null ? Pace.notificationDefaults() : notification;
        if (lease.isNegative() || lease.isZero()) {
            throw new IllegalArgumentException("commhub.dispatch.lease must be positive");
        }
    }

    public static MessageDispatchProperties defaults() {
        return new MessageDispatchProperties(null, null, null, null, null, null);
    }

    /**
     * How hard one traffic class is drained.
     *
     * @param batchSize messages one pass claims
     * @param maxPassesPerTick back-to-back passes a tick may run while pages keep coming back full;
     *     bounds how long one tick holds its thread when a backlog is draining
     * @param concurrency provider calls in flight at once within a pass. Bounded by a semaphore rather
     *     than by the pool: the calls run on virtual threads (AR-07), so the limit protects the provider
     *     and the connection pool, not the JVM
     */
    public record Pace(Duration pollInterval, Integer batchSize, Integer maxPassesPerTick, Integer concurrency) {

        public Pace {
            pollInterval = pollInterval == null ? Duration.ofSeconds(1) : pollInterval;
            batchSize = batchSize == null ? 200 : batchSize;
            maxPassesPerTick = maxPassesPerTick == null ? 10 : maxPassesPerTick;
            concurrency = concurrency == null ? 32 : concurrency;
            if (pollInterval.isNegative() || pollInterval.isZero()) {
                throw new IllegalArgumentException("commhub.dispatch.*.poll-interval must be positive");
            }
            if (batchSize < 1 || maxPassesPerTick < 1 || concurrency < 1) {
                throw new IllegalArgumentException("commhub.dispatch.* sizes must be positive");
            }
        }

        /** Polls often, takes small pages: the p99 of TC-01 is stated in seconds. */
        public static Pace criticalOtpDefaults() {
            return new Pace(Duration.ofMillis(200), 100, 5, 32);
        }

        public static Pace transactionalDefaults() {
            return new Pace(Duration.ofMillis(500), 200, 10, 32);
        }

        /** Polls rarely, takes large pages: throughput matters here and latency does not. */
        public static Pace notificationDefaults() {
            return new Pace(Duration.ofSeconds(1), 500, 20, 64);
        }
    }
}
