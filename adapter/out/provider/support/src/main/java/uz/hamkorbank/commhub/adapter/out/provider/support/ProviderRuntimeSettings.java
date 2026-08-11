package uz.hamkorbank.commhub.adapter.out.provider.support;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import uz.hamkorbank.commhub.adapter.out.persistence.config.ConfigurationCacheProperties;
import uz.hamkorbank.commhub.application.port.out.ProviderConfigRepository;
import uz.hamkorbank.commhub.domain.model.Provider;
import uz.hamkorbank.commhub.domain.model.RateLimit;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * The part of a provider profile that an operator changes at runtime (FR-2.5, FR-2.7, AD-07).
 *
 * <p>This is where the settings promised to {@code provider.endpoint_config} in Phase 7 actually come
 * from now: the throughput limits and the adapter's own transport keys are read from the database and
 * applied without a restart, with the deployment's yaml behind them as the default.
 *
 * <p>What deliberately does <b>not</b> move here: credentials, which arrive as deployment settings from
 * the environment of the pod (SEC-04, ADR-0044), and the settings that decide how the HTTP client is
 * built — base URL, connect and read timeouts, retry and breaker windows. Those are deployment
 * topology rather than routing configuration, and changing them at runtime means rebuilding a client
 * on the sending path; they stay in yaml, where a change is a deploy and is reviewed as one.
 *
 * <p>Values are cached for the configuration refresh interval, so the send path costs a map lookup and
 * a change applies within the same window as the rest of the configuration (NF-07). The clock is
 * {@link System#nanoTime()}: an NTP correction must not extend a cache entry.
 */
public class ProviderRuntimeSettings {

    private final ProviderConfigRepository configuration;
    private final long ttlNanos;
    private final Map<String, CachedProfile> cache = new ConcurrentHashMap<>();

    /**
     * @param configuration provider registry; {@code null} where none is wired, and every adapter then
     *     keeps the settings it was deployed with
     */
    public ProviderRuntimeSettings(ProviderConfigRepository configuration, ConfigurationCacheProperties properties) {
        this.configuration = configuration;
        this.ttlNanos =
                Guard.notNull(properties, "properties").refreshInterval().toNanos();
    }

    /** Adapter settings from the deployment configuration only, without a provider registry. */
    public static ProviderRuntimeSettings configurationOnly() {
        return new ProviderRuntimeSettings(null, ConfigurationCacheProperties.defaults());
    }

    /**
     * Throughput limits of the provider (FR-2.5).
     *
     * @param fallback limits from the deployment configuration, used while the profile has none of its
     *     own — a provider row without a {@code rate_limit_config} must not silently become unlimited
     */
    public RateLimit rateLimitOf(String providerCode, RateLimit fallback) {
        return profile(providerCode)
                .map(CachedProfile::rateLimit)
                .filter(limit -> !limit.isUnlimited())
                .orElse(fallback);
    }

    /**
     * Transport settings the adapter itself interprets (§10.1 {@code provider.endpoint_config}).
     *
     * <p>An empty map means "nothing overridden"; the adapter then keeps its configured defaults.
     */
    public Map<String, String> endpointConfigOf(String providerCode) {
        return profile(providerCode).map(CachedProfile::endpointConfig).orElseGet(Map::of);
    }

    /** Drops the cache; used by the tests and after a configuration change on this instance. */
    public void invalidate() {
        cache.clear();
    }

    private Optional<CachedProfile> profile(String providerCode) {
        Guard.notBlank(providerCode, "providerCode");
        CachedProfile cached = cache.get(providerCode);
        if (cached != null && !cached.isExpired(ttlNanos)) {
            return Optional.of(cached);
        }
        return load(providerCode);
    }

    /**
     * Reads the profile behind a provider code.
     *
     * <p>A provider that is not registered yet — or a database that cannot be reached — leaves the
     * adapter on its configured defaults instead of failing the send: the limits exist to protect the
     * provider, not to become a reason messages stop going out.
     */
    private Optional<CachedProfile> load(String providerCode) {
        if (configuration == null) {
            return Optional.empty();
        }
        try {
            Optional<Provider> provider = configuration.findProviderByCode(ProviderCode.of(providerCode));
            if (provider.isEmpty()) {
                return Optional.empty();
            }
            CachedProfile profile = new CachedProfile(
                    provider.get().rateLimit(),
                    configuration.endpointConfig(provider.get().id()),
                    System.nanoTime());
            cache.put(providerCode, profile);
            return Optional.of(profile);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private record CachedProfile(RateLimit rateLimit, Map<String, String> endpointConfig, long readAtNanos) {

        private CachedProfile {
            endpointConfig = endpointConfig == null ? Map.of() : Map.copyOf(endpointConfig);
        }

        private boolean isExpired(long ttlNanos) {
            return System.nanoTime() - readAtNanos >= ttlNanos;
        }
    }
}
