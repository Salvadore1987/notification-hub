package uz.hamkorbank.commhub.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

/**
 * Routing configuration held in PostgreSQL and editable from the admin panel (§10.1 {@code channel},
 * {@code provider}, {@code routing_policy}, FR-2.1…FR-2.7).
 *
 * <p>{@link #routingConfiguration(StreamId)} returns an immutable snapshot the {@code Router} decides
 * against. Adapters are expected to serve it from a cache refreshed within 30 s of a change, which is
 * how "applied without a restart" is met without the core knowing about caching (AD-07, NF-07).
 */
public interface ProviderConfigRepository {

    /** Snapshot of channels, providers, policies and the defaults of the submitting stream (AD-07). */
    RoutingConfiguration routingConfiguration(StreamId streamId);

    Optional<ChannelConfig> findChannel(Channel channel);

    /** Every configured channel, for the administration screens (FR-2.7). */
    List<ChannelConfig> findChannels();

    Optional<Provider> findProvider(ProviderId providerId);

    Optional<Provider> findProviderByCode(ProviderCode providerCode);

    /** Providers of a channel in configuration order (FR-2.2). */
    List<Provider> findProviders(Channel channel);

    /** Every configured provider, on every channel (FR-2.1). */
    List<Provider> findAllProviders();

    List<RoutingPolicy> findPolicies();

    Optional<RoutingPolicy> findPolicy(RoutingPolicyId policyId);

    Provider save(Provider provider);

    ChannelConfig save(ChannelConfig channelConfig);

    RoutingPolicy save(RoutingPolicy policy);

    /**
     * Transport settings of a provider adapter (§10.1 {@code provider.endpoint_config}).
     *
     * <p>An opaque map: the Hub stores and returns it, only the adapter behind {@code adapterType}
     * knows what its keys mean, which is what keeps a new provider a new adapter (AR-04). Credentials
     * never belong here — they are deployment settings read from the environment (SEC-04, ADR-0044).
     */
    Map<String, String> endpointConfig(ProviderId providerId);

    void saveEndpointConfig(ProviderId providerId, Map<String, String> endpointConfig);

    /** Health observed by the monitor; separated from {@link #save} to keep the write small (PR-02). */
    void updateHealth(ProviderId providerId, ProviderHealthStatus health, String detail, Instant checkedAt);

    /** Removes a provider that no channel references any more (FR-2.1). */
    void deleteProvider(ProviderId providerId);

    void deletePolicy(RoutingPolicyId policyId);
}
