package uz.hamkorbank.commhub.application.port.out;

import java.util.List;
import java.util.Optional;
import uz.hamkorbank.commhub.domain.model.ChannelConfig;
import uz.hamkorbank.commhub.domain.model.Provider;
import uz.hamkorbank.commhub.domain.model.RoutingPolicy;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.model.vo.ProviderId;
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

    Optional<Provider> findProvider(ProviderId providerId);

    Optional<Provider> findProviderByCode(ProviderCode providerCode);

    /** Providers of a channel in configuration order (FR-2.2). */
    List<Provider> findProviders(Channel channel);

    List<RoutingPolicy> findPolicies();

    Provider save(Provider provider);

    ChannelConfig save(ChannelConfig channelConfig);

    RoutingPolicy save(RoutingPolicy policy);
}
