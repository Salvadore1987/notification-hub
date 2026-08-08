package uz.hamkorbank.commhub.adapter.out.persistence.config;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;
import uz.hamkorbank.commhub.application.port.out.ProviderConfigRepository;
import uz.hamkorbank.commhub.domain.model.ChannelConfig;
import uz.hamkorbank.commhub.domain.model.Provider;
import uz.hamkorbank.commhub.domain.model.RoutingPolicy;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.ProviderHealthStatus;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.model.vo.ProviderId;
import uz.hamkorbank.commhub.domain.model.vo.RoutingPolicyId;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;
import uz.hamkorbank.commhub.domain.service.RoutingConfiguration;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Keeps the routing snapshot in memory so that a message does not pay for reading the configuration
 * (AD-07, NF-07).
 *
 * <p>Every send routes, and routing needs the whole picture: channels, providers, policies and the
 * defaults of the submitting stream. Reading that per message would put four queries in front of every
 * OTP accept, on a path with a 200 ms budget (FR-1.7, TC-01). The configuration, meanwhile, changes a
 * few times a month.
 *
 * <p><b>Only {@link #routingConfiguration} is cached.</b> The other methods go to the database on every
 * call, and that is a design decision rather than an omission: they serve the administration use cases,
 * which load an aggregate in order to <em>change</em> it. Handing those a cached {@link Provider} would
 * hand several threads the same mutable object and persist whichever edit lost the race. The routing
 * path only reads, so sharing one snapshot across virtual threads is safe there.
 *
 * <p>Staleness is bounded by {@link ConfigurationCacheProperties#refreshInterval()} and nothing else:
 * a local write invalidates the local snapshot immediately, other instances pick the change up on their
 * next refresh. There is deliberately no invalidation message between instances — that would be another
 * channel to operate and to fail, and NF-07 already allows 30 seconds.
 *
 * <p>The clock is {@link System#nanoTime()}: expiry must not move when NTP corrects the wall clock,
 * or a snapshot can outlive its window by however far the correction jumped.
 */
@Repository
@Primary
public class CachingProviderConfigRepository implements ProviderConfigRepository {

    private static final Logger LOG = LoggerFactory.getLogger(CachingProviderConfigRepository.class);

    private final ProviderConfigPersistenceAdapter delegate;
    private final ConfigurationCacheProperties properties;
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>();

    public CachingProviderConfigRepository(
            ProviderConfigPersistenceAdapter delegate, ConfigurationCacheProperties properties) {
        this.delegate = Guard.notNull(delegate, "delegate");
        this.properties = Guard.notNull(properties, "properties");
    }

    /**
     * Snapshot the router decides against (AD-07).
     *
     * <p>Two threads missing at once both load and one of them wins the write; the loser's entry is
     * simply missed again later. A lock here would serialise every first send of a generation, which is
     * a far worse trade than an occasional duplicated read of four small tables.
     */
    @Override
    public RoutingConfiguration routingConfiguration(StreamId streamId) {
        if (!properties.enabled()) {
            return delegate.routingConfiguration(streamId);
        }
        Snapshot current = snapshot.get();
        if (current == null || current.isExpired(properties.refreshInterval().toNanos())) {
            current = Snapshot.empty();
        }
        RoutingConfiguration cached = current.configurations().get(streamId);
        if (cached != null) {
            return cached;
        }
        RoutingConfiguration loaded = configurationOf(streamId);
        snapshot.set(current.with(streamId, loaded));
        return loaded;
    }

    /**
     * Reloads the snapshot from the database (AD-07).
     *
     * <p>Called by the scheduler so that expiry is normally reached with a fresh snapshot already in
     * place, and by every local write. A failed reload keeps the previous snapshot: routing with a
     * configuration that is a minute old beats routing with none at all.
     */
    public void refresh() {
        Snapshot current = snapshot.get();
        if (current == null) {
            return;
        }
        try {
            snapshot.set(current.reloaded(this::configurationOf));
        } catch (RuntimeException e) {
            LOG.warn("routing configuration refresh failed, keeping the previous snapshot", e);
        }
    }

    /** Drops the snapshot; the next routing decision reloads it (AD-07). */
    public void invalidate() {
        snapshot.set(null);
    }

    private RoutingConfiguration configurationOf(StreamId streamId) {
        return delegate.routingConfiguration(streamId);
    }

    // --- Uncached: the administration path, which loads aggregates in order to change them ---------

    @Override
    public Optional<ChannelConfig> findChannel(Channel channel) {
        return delegate.findChannel(channel);
    }

    @Override
    public List<ChannelConfig> findChannels() {
        return delegate.findChannels();
    }

    @Override
    public Optional<Provider> findProvider(ProviderId providerId) {
        return delegate.findProvider(providerId);
    }

    @Override
    public Optional<Provider> findProviderByCode(ProviderCode providerCode) {
        return delegate.findProviderByCode(providerCode);
    }

    @Override
    public List<Provider> findProviders(Channel channel) {
        return delegate.findProviders(channel);
    }

    @Override
    public List<Provider> findAllProviders() {
        return delegate.findAllProviders();
    }

    @Override
    public List<RoutingPolicy> findPolicies() {
        return delegate.findPolicies();
    }

    @Override
    public Optional<RoutingPolicy> findPolicy(RoutingPolicyId policyId) {
        return delegate.findPolicy(policyId);
    }

    @Override
    public Map<String, String> endpointConfig(ProviderId providerId) {
        return delegate.endpointConfig(providerId);
    }

    // --- Writes: apply, then drop the snapshot so this instance sees its own edit at once ----------

    @Override
    public Provider save(Provider provider) {
        Provider saved = delegate.save(provider);
        invalidate();
        return saved;
    }

    @Override
    public ChannelConfig save(ChannelConfig channelConfig) {
        ChannelConfig saved = delegate.save(channelConfig);
        invalidate();
        return saved;
    }

    @Override
    public RoutingPolicy save(RoutingPolicy policy) {
        RoutingPolicy saved = delegate.save(policy);
        invalidate();
        return saved;
    }

    @Override
    public void saveEndpointConfig(ProviderId providerId, Map<String, String> endpointConfig) {
        delegate.saveEndpointConfig(providerId, endpointConfig);
        invalidate();
    }

    @Override
    public void updateHealth(ProviderId providerId, ProviderHealthStatus health, String detail, Instant checkedAt) {
        delegate.updateHealth(providerId, health, detail, checkedAt);
        invalidate();
    }

    @Override
    public void deleteProvider(ProviderId providerId) {
        delegate.deleteProvider(providerId);
        invalidate();
    }

    @Override
    public void deletePolicy(RoutingPolicyId policyId) {
        delegate.deletePolicy(policyId);
        invalidate();
    }

    /**
     * One generation of the configuration, per stream that has asked for it.
     *
     * <p>Per stream because the snapshot carries the stream's defaults (FR-2.4, TC-02), and streams are
     * counted in dozens (§18.4). A generation is replaced whole, never edited: readers either see the
     * old picture or the new one, and never a channel from one with a provider list from the other.
     */
    private record Snapshot(Map<StreamId, RoutingConfiguration> configurations, long loadedAtNanos) {

        private static Snapshot of(Map<StreamId, RoutingConfiguration> configurations) {
            return new Snapshot(Map.copyOf(configurations), System.nanoTime());
        }

        private static Snapshot empty() {
            return of(Map.of());
        }

        private Snapshot with(StreamId streamId, RoutingConfiguration configuration) {
            Map<StreamId, RoutingConfiguration> merged = new HashMap<>(configurations);
            merged.put(streamId, configuration);
            return new Snapshot(Map.copyOf(merged), loadedAtNanos);
        }

        private boolean isExpired(long ttlNanos) {
            return System.nanoTime() - loadedAtNanos >= ttlNanos;
        }

        private Snapshot reloaded(Function<StreamId, RoutingConfiguration> loader) {
            return Snapshot.of(
                    configurations.keySet().stream().collect(Collectors.toMap(streamId -> streamId, loader)));
        }
    }
}
