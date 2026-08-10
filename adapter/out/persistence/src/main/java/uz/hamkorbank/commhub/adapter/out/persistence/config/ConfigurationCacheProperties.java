package uz.hamkorbank.commhub.adapter.out.persistence.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Freshness of the cached routing configuration (AD-07, NF-07).
 *
 * <p>NF-07 gives a configuration change 30 seconds to take effect, so the ceiling is not a preference:
 * the refresh interval is capped at 30 s and validated on startup rather than silently accepted.
 *
 * @param enabled off serves every routing decision straight from PostgreSQL; useful for a test that
 *     wants to see its own edit immediately, never for production traffic
 * @param refreshInterval how often the snapshot is reloaded, and therefore the worst-case staleness
 */
@ConfigurationProperties("commhub.config.cache")
public record ConfigurationCacheProperties(Boolean enabled, Duration refreshInterval) {

    /** Upper bound imposed by NF-07: a change must be applied within 30 s without a restart. */
    public static final Duration MAX_REFRESH_INTERVAL = Duration.ofSeconds(30);

    public static final Duration DEFAULT_REFRESH_INTERVAL = Duration.ofSeconds(10);

    public ConfigurationCacheProperties {
        enabled = enabled == null || enabled;
        refreshInterval = refreshInterval == null ? DEFAULT_REFRESH_INTERVAL : refreshInterval;
        if (refreshInterval.isNegative() || refreshInterval.isZero()) {
            throw new IllegalArgumentException("commhub.config.cache.refresh-interval must be positive");
        }
        if (refreshInterval.compareTo(MAX_REFRESH_INTERVAL) > 0) {
            throw new IllegalArgumentException(
                    "commhub.config.cache.refresh-interval must not exceed 30s (NF-07): " + refreshInterval);
        }
    }

    public static ConfigurationCacheProperties defaults() {
        return new ConfigurationCacheProperties(null, null);
    }
}
