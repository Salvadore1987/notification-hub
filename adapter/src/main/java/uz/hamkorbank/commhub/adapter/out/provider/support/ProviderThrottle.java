package uz.hamkorbank.commhub.adapter.out.provider.support;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;
import uz.hamkorbank.commhub.domain.model.RateLimit;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Keeps a provider inside the limits it agreed to (FR-2.5, §18.2).
 *
 * <p>Three limits, all optional and all taken from the domain's {@link RateLimit}: a sustained rate, a
 * per-minute ceiling, and the anti-spam rule that is specific to SMS — SMS Gate refuses a number that
 * has had more than 50 SMS in an hour with {@code status.code = 1} (§18.2). Pre-empting that refusal
 * matters: a message the Hub holds back can still fail over to the other provider of the channel, a
 * message the provider refuses has already used its attempt.
 *
 * <p>Per instance and in memory, like the inbound limiter of IR-02, and for the same reason: the
 * alternative puts a network round trip in front of every send, including OTP. The cluster-wide limit
 * is the configured one divided by the number of replicas — which is also why the per-recipient ceiling
 * is configured slightly below the provider's own.
 *
 * <p>The clock is {@link System#nanoTime()}: refill and window arithmetic must not move when NTP
 * corrects the wall clock, or an hour of quota is handed out twice.
 */
@Component
public class ProviderThrottle {

    /** Recipients tracked at once before the oldest windows are dropped; ~64 bytes each. */
    private static final int MAX_TRACKED_RECIPIENTS = 200_000;

    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final long NANOS_PER_MINUTE = 60L * NANOS_PER_SECOND;
    private static final long NANOS_PER_HOUR = 60L * NANOS_PER_MINUTE;

    private final Map<String, TokenBucket> tpsBuckets = new ConcurrentHashMap<>();
    private final Map<String, Window> minuteWindows = new ConcurrentHashMap<>();
    private final Map<String, Window> recipientWindows = new ConcurrentHashMap<>();

    /**
     * Takes the permits one message needs.
     *
     * @param recipient address the per-recipient ceiling counts against; {@code null} skips that check
     * @return the limit that was hit, or empty when the message may be sent
     */
    public Optional<String> acquire(String providerCode, RateLimit limit, String recipient) {
        Guard.notBlank(providerCode, "providerCode");
        Guard.notNull(limit, "limit");
        if (limit.isUnlimited()) {
            return Optional.empty();
        }
        long now = System.nanoTime();
        if (limit.hasTpsLimit() && !bucket(providerCode, limit).tryAcquire(now)) {
            return Optional.of("provider rate of %d TPS is exhausted".formatted(limit.tps()));
        }
        if (limit.hasPerMinuteLimit()
                && !window(minuteWindows, providerCode, NANOS_PER_MINUTE).tryAcquire(now, limit.perMinute())) {
            return Optional.of("provider ceiling of %d messages per minute is reached".formatted(limit.perMinute()));
        }
        if (recipient != null && limit.hasPerRecipientLimit()) {
            String key = providerCode + '|' + recipient;
            pruneRecipients(now);
            if (!window(recipientWindows, key, NANOS_PER_HOUR).tryAcquire(now, limit.perRecipientPerHour())) {
                return Optional.of("recipient reached %d messages per hour on this provider (§18.2)"
                        .formatted(limit.perRecipientPerHour()));
            }
        }
        return Optional.empty();
    }

    /** Drops every counter; used by the tests and after a configuration change (AD-07). */
    public void reset() {
        tpsBuckets.clear();
        minuteWindows.clear();
        recipientWindows.clear();
    }

    private TokenBucket bucket(String providerCode, RateLimit limit) {
        return tpsBuckets.computeIfAbsent(providerCode, key -> new TokenBucket(limit.tps()));
    }

    private static Window window(Map<String, Window> windows, String key, long lengthNanos) {
        return windows.computeIfAbsent(key, ignored -> new Window(lengthNanos));
    }

    /**
     * Keeps the per-recipient map bounded.
     *
     * <p>Entries whose hour has elapsed carry no information, so dropping them loses nothing; the sweep
     * only runs once the map is large enough for that to matter.
     */
    private void pruneRecipients(long now) {
        if (recipientWindows.size() <= MAX_TRACKED_RECIPIENTS) {
            return;
        }
        recipientWindows.values().removeIf(window -> window.isElapsed(now));
    }

    /** Refilling bucket: permits accrue continuously, the depth is one second of rate. */
    private static final class TokenBucket {

        private final double permitsPerSecond;
        private final double capacity;
        private double tokens;
        private long lastRefillNanos = System.nanoTime();

        private TokenBucket(int permitsPerSecond) {
            this.permitsPerSecond = permitsPerSecond;
            this.capacity = permitsPerSecond;
            this.tokens = permitsPerSecond;
        }

        private synchronized boolean tryAcquire(long now) {
            double elapsedSeconds = Math.max(0L, now - lastRefillNanos) / (double) NANOS_PER_SECOND;
            lastRefillNanos = now;
            tokens = Math.min(capacity, tokens + elapsedSeconds * permitsPerSecond);
            if (tokens < 1.0d) {
                return false;
            }
            tokens -= 1.0d;
            return true;
        }
    }

    /**
     * Fixed window counter.
     *
     * <p>Fixed rather than sliding on purpose: a sliding window has to remember every timestamp, and the
     * per-recipient counter would then hold 50 instants for every number the Bank writes to. The
     * imprecision at a window boundary is acceptable for a ceiling that exists to stay under someone
     * else's ceiling.
     */
    private static final class Window {

        private final long lengthNanos;
        private final AtomicInteger count = new AtomicInteger();
        private volatile long startedAt = System.nanoTime();

        private Window(long lengthNanos) {
            this.lengthNanos = lengthNanos;
        }

        private synchronized boolean tryAcquire(long now, int max) {
            if (isElapsed(now)) {
                startedAt = now;
                count.set(0);
            }
            if (count.get() >= max) {
                return false;
            }
            count.incrementAndGet();
            return true;
        }

        private boolean isElapsed(long now) {
            return now - startedAt >= lengthNanos;
        }
    }
}
