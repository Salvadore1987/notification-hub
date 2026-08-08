package uz.hamkorbank.commhub.adapter.in.rest.ratelimit;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Per-stream request limits of the source-system API (IR-02).
 *
 * <p>Since Phase 8 these are the fallback rather than the source: a stream carries its own limit in
 * {@code stream.rate_limit_config}, editable from the admin panel and applied without a restart
 * (AD-07). The values here still apply to every stream that has none of its own, which is what keeps a
 * new source system from starving an OTP stream before anyone has configured it.
 *
 * <p>The limit counts accepted requests, not messages: a chunk of ten thousand batch items is one
 * request here and is bounded by the chunk size instead.
 *
 * @param enabled whether the limiter runs at all; off in environments that front the Hub with a gateway
 * @param defaultPermitsPerSecond limit applied to a stream with no entry of its own
 * @param defaultBurst requests a stream may send back-to-back before the rate applies
 * @param streams per-stream overrides, keyed by {@code streamId}; the registry wins over these
 * @param registryCacheTtl how long a limit read from the stream registry is reused before it is read
 *     again; the limiter runs on every accepted request, so this must not be a database call per
 *     request (NF-07 bounds it at 30 s like the rest of the configuration)
 */
@ConfigurationProperties("commhub.rest.rate-limit")
public record RateLimitProperties(
        Boolean enabled,
        Double defaultPermitsPerSecond,
        Integer defaultBurst,
        Map<String, StreamLimit> streams,
        Duration registryCacheTtl) {

    public static final double DEFAULT_PERMITS_PER_SECOND = 200.0;

    public static final int DEFAULT_BURST = 400;

    public static final Duration DEFAULT_REGISTRY_CACHE_TTL = Duration.ofSeconds(10);

    public RateLimitProperties {
        enabled = enabled == null || enabled;
        defaultPermitsPerSecond =
                defaultPermitsPerSecond == null ? DEFAULT_PERMITS_PER_SECOND : defaultPermitsPerSecond;
        defaultBurst = defaultBurst == null ? DEFAULT_BURST : defaultBurst;
        streams = streams == null ? Map.of() : Map.copyOf(streams);
        registryCacheTtl = registryCacheTtl == null ? DEFAULT_REGISTRY_CACHE_TTL : registryCacheTtl;
        if (defaultPermitsPerSecond <= 0) {
            throw new IllegalArgumentException("commhub.rest.rate-limit.default-permits-per-second must be positive");
        }
        if (defaultBurst < 1) {
            throw new IllegalArgumentException("commhub.rest.rate-limit.default-burst must be at least 1");
        }
        if (registryCacheTtl.isNegative() || registryCacheTtl.isZero()) {
            throw new IllegalArgumentException("commhub.rest.rate-limit.registry-cache-ttl must be positive");
        }
    }

    public static RateLimitProperties defaults() {
        return new RateLimitProperties(null, null, null, null, null);
    }

    /** Effective limit of one stream: its own entry where there is one, the defaults otherwise. */
    public StreamLimit limitOf(String streamId) {
        StreamLimit configured = streams.get(streamId);
        if (configured == null) {
            return new StreamLimit(defaultPermitsPerSecond, defaultBurst);
        }
        return new StreamLimit(
                configured.permitsPerSecond() == null ? defaultPermitsPerSecond : configured.permitsPerSecond(),
                configured.burst() == null ? defaultBurst : configured.burst());
    }

    /**
     * Limit of one stream.
     *
     * @param permitsPerSecond sustained rate; {@code null} takes the default
     * @param burst bucket depth, i.e. how far a stream may run ahead of its rate; {@code null} takes
     *     the default
     */
    public record StreamLimit(Double permitsPerSecond, Integer burst) {

        public StreamLimit {
            if (permitsPerSecond != null && permitsPerSecond <= 0) {
                throw new IllegalArgumentException("permits-per-second must be positive");
            }
            if (burst != null && burst < 1) {
                throw new IllegalArgumentException("burst must be at least 1");
            }
        }
    }
}
