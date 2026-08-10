package uz.hamkorbank.commhub.adapter.in.rest.ratelimit;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import uz.hamkorbank.commhub.adapter.in.rest.ratelimit.RateLimitProperties.StreamLimit;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Token bucket per source stream, guarding the synchronous API (IR-02).
 *
 * <p>Per instance and in memory on purpose. A shared counter would put a network call in front of
 * every OTP accept, which is the one path with a 200 ms budget (FR-1.7); with the limit divided over
 * the instances behind the balancer, the cluster-wide rate stays predictable and the check costs a few
 * nanoseconds. A distributed limiter belongs with the quota counters of FR-2.6, which already live in
 * the database and are counted per message rather than per request.
 *
 * <p>The limit itself comes from {@link StreamLimits}: the stream registry where the stream has one,
 * the deployment defaults otherwise (IR-02, AD-07). A stream whose limit changes keeps its bucket —
 * only the rate it refills at changes, which is what an operator raising a limit during an incident
 * expects to happen.
 *
 * <p>The clock is {@link System#nanoTime()} and not the {@code ClockPort}: refilling a bucket needs
 * elapsed time that never jumps, and a wall clock that steps backwards during an NTP correction would
 * hand out free permits.
 */
@Component
public class StreamRateLimiter {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private final RateLimitProperties properties;
    private final StreamLimits limits;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public StreamRateLimiter(RateLimitProperties properties, StreamLimits limits) {
        this.properties = Guard.notNull(properties, "properties");
        this.limits = Guard.notNull(limits, "limits");
    }

    /**
     * Consumes one permit of the stream.
     *
     * @throws RateLimitExceededException when the stream is over its rate; the exception carries the
     *     delay that becomes {@code Retry-After}
     */
    public void check(String streamId) {
        check(streamId, System.nanoTime());
    }

    void check(String streamId, long nowNanos) {
        Guard.notBlank(streamId, "streamId");
        if (!properties.enabled()) {
            return;
        }
        StreamLimit limit = limits.of(streamId);
        Bucket bucket = buckets.computeIfAbsent(streamId, id -> new Bucket(limit.burst()));
        long waitNanos = bucket.acquire(nowNanos, limit.permitsPerSecond(), limit.burst());
        if (waitNanos > 0) {
            throw new RateLimitExceededException(streamId, Duration.ofNanos(waitNanos));
        }
    }

    /** Number of streams currently tracked; a stream costs one small object until the process ends. */
    int trackedStreams() {
        return buckets.size();
    }

    /**
     * A bucket refilled continuously at the configured rate.
     *
     * <p>Synchronized rather than lock-free: the critical section is a handful of arithmetic
     * operations, and contention on it only ever happens between requests of the same stream, which are
     * already serialized by that stream's own rate.
     */
    private static final class Bucket {

        private double tokens;
        private long lastRefillNanos;
        private boolean started;

        private Bucket(int burst) {
            this.tokens = burst;
        }

        /**
         * Takes one permit at the rate currently configured for the stream.
         *
         * <p>The rate is a parameter and not a field on purpose: a limit raised in the admin panel must
         * take effect on the existing bucket, not only on the bucket of a stream that has been idle long
         * enough to be recreated (IR-02, AD-07).
         *
         * @return 0 when the permit was granted, otherwise the nanoseconds until the next one
         */
        private synchronized long acquire(long nowNanos, double permitsPerSecond, int burst) {
            refill(nowNanos, permitsPerSecond, burst);
            if (tokens >= 1.0d) {
                tokens -= 1.0d;
                return 0L;
            }
            double missing = 1.0d - tokens;
            return (long) Math.ceil(missing / permitsPerSecond * NANOS_PER_SECOND);
        }

        private void refill(long nowNanos, double permitsPerSecond, int burst) {
            if (!started) {
                started = true;
                lastRefillNanos = nowNanos;
                tokens = Math.min(tokens, burst);
                return;
            }
            long elapsed = nowNanos - lastRefillNanos;
            if (elapsed <= 0) {
                tokens = Math.min(tokens, burst);
                return;
            }
            lastRefillNanos = nowNanos;
            tokens = Math.min(burst, tokens + (double) elapsed / NANOS_PER_SECOND * permitsPerSecond);
        }
    }
}
