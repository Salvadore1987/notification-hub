package uz.hamkorbank.commhub.adapter.in.rest.ratelimit;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Per-stream request limits of the source-system API (IR-02).
 *
 * <p>Configuration rather than database rows for now: the stream registry of Phase 8 is where an
 * operator will edit these without a restart (AD-07), and until it exists a limit that ships with the
 * deployment is better than none — an OTP stream must not be starved because a bulk stream loops.
 *
 * <p>The limit counts accepted requests, not messages: a chunk of ten thousand batch items is one
 * request here and is bounded by the chunk size instead.
 *
 * @param enabled whether the limiter runs at all; off in environments that front the Hub with a gateway
 * @param defaultPermitsPerSecond limit applied to a stream with no entry of its own
 * @param defaultBurst requests a stream may send back-to-back before the rate applies
 * @param streams per-stream overrides, keyed by {@code streamId}
 */
@ConfigurationProperties("commhub.rest.rate-limit")
public record RateLimitProperties(
        Boolean enabled, Double defaultPermitsPerSecond, Integer defaultBurst, Map<String, StreamLimit> streams) {

    public static final double DEFAULT_PERMITS_PER_SECOND = 200.0;

    public static final int DEFAULT_BURST = 400;

    public RateLimitProperties {
        enabled = enabled == null || enabled;
        defaultPermitsPerSecond =
                defaultPermitsPerSecond == null ? DEFAULT_PERMITS_PER_SECOND : defaultPermitsPerSecond;
        defaultBurst = defaultBurst == null ? DEFAULT_BURST : defaultBurst;
        streams = streams == null ? Map.of() : Map.copyOf(streams);
        if (defaultPermitsPerSecond <= 0) {
            throw new IllegalArgumentException("commhub.rest.rate-limit.default-permits-per-second must be positive");
        }
        if (defaultBurst < 1) {
            throw new IllegalArgumentException("commhub.rest.rate-limit.default-burst must be at least 1");
        }
    }

    public static RateLimitProperties defaults() {
        return new RateLimitProperties(null, null, null, null);
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
