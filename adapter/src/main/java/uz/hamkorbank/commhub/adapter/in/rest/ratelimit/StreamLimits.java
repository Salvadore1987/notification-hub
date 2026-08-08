package uz.hamkorbank.commhub.adapter.in.rest.ratelimit;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import uz.hamkorbank.commhub.adapter.in.rest.ratelimit.RateLimitProperties.StreamLimit;
import uz.hamkorbank.commhub.application.port.out.StreamRepository;
import uz.hamkorbank.commhub.domain.model.RateLimit;
import uz.hamkorbank.commhub.domain.model.Stream;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Effective request limit of a stream: the registry first, the deployment defaults behind it (IR-02,
 * AD-07).
 *
 * <p>This is where the per-stream overrides promised in Phase 6 actually live now — in
 * {@code stream.rate_limit_config}, editable from the admin panel and applied without a restart. The
 * yaml values stay as the fallback for a stream that has no limit of its own, and as the only source
 * before the registry is populated.
 *
 * <p>The lookup is cached per stream for the same interval as the routing snapshot: the limiter is
 * consulted on every accepted request, including OTP, and a database read there would cost more than
 * the limit saves. A change to a stream's limit therefore applies within that window, which is the same
 * promise NF-07 makes for the rest of the configuration.
 *
 * <p>{@code tps} of the domain {@link RateLimit} is the sustained rate; the burst comes from
 * {@code perMinute} when it is set — an operator who allows 12 000 requests a minute has said what a
 * burst may look like — and otherwise defaults to two seconds of rate.
 */
@Component
public class StreamLimits {

    /** Burst allowed when only a sustained rate is configured: two seconds of it. */
    private static final int DEFAULT_BURST_SECONDS = 2;

    private final StreamRepository streams;
    private final RateLimitProperties properties;
    private final Duration ttl;
    private final Map<String, CachedLimit> cache = new ConcurrentHashMap<>();

    /**
     * @param streams stream registry; {@code null} in a context where none is wired, and the limits
     *     then come from configuration alone — exactly as they did before the registry existed
     */
    public StreamLimits(StreamRepository streams, RateLimitProperties properties) {
        this.streams = streams;
        this.properties = Guard.notNull(properties, "properties");
        this.ttl = properties.registryCacheTtl();
    }

    /** Limits from the deployment configuration only, without a stream registry (IR-02). */
    public static StreamLimits configurationOnly(RateLimitProperties properties) {
        return new StreamLimits(null, properties);
    }

    /** Limit that applies to the next request of this stream. */
    public StreamLimit of(String streamId) {
        Guard.notBlank(streamId, "streamId");
        CachedLimit cached = cache.get(streamId);
        if (cached != null && !cached.isExpired(ttl.toNanos())) {
            return cached.limit();
        }
        StreamLimit limit = resolve(streamId);
        cache.put(streamId, new CachedLimit(limit, System.nanoTime()));
        return limit;
    }

    private StreamLimit resolve(String streamId) {
        return fromRegistry(streamId).orElseGet(() -> properties.limitOf(streamId));
    }

    /**
     * Limit stored on the stream, when it has one.
     *
     * <p>A registry that cannot be read falls back to the configured defaults instead of failing the
     * request: the limiter protects the Hub, and it must not become the reason the Hub rejects traffic.
     */
    private Optional<StreamLimit> fromRegistry(String streamId) {
        if (streams == null) {
            return Optional.empty();
        }
        try {
            return streams.findById(uz.hamkorbank.commhub.domain.model.vo.StreamId.of(streamId))
                    .map(Stream::rateLimit)
                    .filter(RateLimit::hasTpsLimit)
                    .map(StreamLimits::toStreamLimit);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static StreamLimit toStreamLimit(RateLimit rateLimit) {
        int burst = rateLimit.hasPerMinuteLimit()
                ? rateLimit.perMinute()
                : Math.max(1, rateLimit.tps() * DEFAULT_BURST_SECONDS);
        return new StreamLimit((double) rateLimit.tps(), burst);
    }

    /** One resolved limit with the moment it was read; {@code nanoTime} so NTP cannot extend it. */
    private record CachedLimit(StreamLimit limit, long readAtNanos) {

        private boolean isExpired(long ttlNanos) {
            return System.nanoTime() - readAtNanos >= ttlNanos;
        }
    }
}
