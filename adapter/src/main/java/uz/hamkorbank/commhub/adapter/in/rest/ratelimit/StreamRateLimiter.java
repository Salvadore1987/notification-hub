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
 * <p>The clock is {@link System#nanoTime()} and not the {@code ClockPort}: refilling a bucket needs
 * elapsed time that never jumps, and a wall clock that steps backwards during an NTP correction would
 * hand out free permits.
 */
@Component
public class StreamRateLimiter {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private final RateLimitProperties properties;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public StreamRateLimiter(RateLimitProperties properties) {
        this.properties = Guard.notNull(properties, "properties");
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
        StreamLimit limit = properties.limitOf(streamId);
        Bucket bucket = buckets.computeIfAbsent(streamId, id -> new Bucket(limit.permitsPerSecond(), limit.burst()));
        long waitNanos = bucket.acquire(nowNanos);
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

        private final double permitsPerSecond;
        private final double burst;

        private double tokens;
        private long lastRefillNanos;
        private boolean started;

        private Bucket(double permitsPerSecond, int burst) {
            this.permitsPerSecond = permitsPerSecond;
            this.burst = burst;
            this.tokens = burst;
        }

        /** @return 0 when the permit was granted, otherwise the nanoseconds until the next one */
        private synchronized long acquire(long nowNanos) {
            refill(nowNanos);
            if (tokens >= 1.0d) {
                tokens -= 1.0d;
                return 0L;
            }
            double missing = 1.0d - tokens;
            return (long) Math.ceil(missing / permitsPerSecond * NANOS_PER_SECOND);
        }

        private void refill(long nowNanos) {
            if (!started) {
                started = true;
                lastRefillNanos = nowNanos;
                return;
            }
            long elapsed = nowNanos - lastRefillNanos;
            if (elapsed <= 0) {
                return;
            }
            lastRefillNanos = nowNanos;
            tokens = Math.min(burst, tokens + (double) elapsed / NANOS_PER_SECOND * permitsPerSecond);
        }
    }
}
