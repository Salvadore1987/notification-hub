package uz.hamkorbank.commhub.domain.service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import uz.hamkorbank.commhub.domain.model.ChannelConfig;
import uz.hamkorbank.commhub.domain.model.Provider;
import uz.hamkorbank.commhub.domain.model.RoutingPolicy;
import uz.hamkorbank.commhub.domain.model.Stream;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.Priority;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.model.vo.ProviderId;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Immutable snapshot of the routing configuration a single decision is taken against (AD-07).
 *
 * <p>Assembled by the application layer from the database (channels, providers, policies, stream
 * defaults) and refreshed when the configuration changes, which is how "applied without a restart in
 * ≤ 30 s" is realised without the domain knowing anything about caching or persistence (AD-07, NF-07).
 *
 * @param streamDefaults defaults of the submitting stream (FR-2.4, TC-02)
 */
public record RoutingConfiguration(
        Map<Channel, ChannelConfig> channels,
        List<Provider> providers,
        List<RoutingPolicy> policies,
        Stream.Defaults streamDefaults) {

    public RoutingConfiguration {
        channels = channels == null || channels.isEmpty() ? Map.of() : Map.copyOf(channels);
        providers = Guard.copyOf(providers);
        policies = Guard.copyOf(policies);
        streamDefaults = streamDefaults == null ? Stream.Defaults.none() : streamDefaults;
    }

    public static RoutingConfiguration of(
            Map<Channel, ChannelConfig> channels, List<Provider> providers, List<RoutingPolicy> policies) {
        return new RoutingConfiguration(channels, providers, policies, Stream.Defaults.none());
    }

    public RoutingConfiguration withStreamDefaults(Stream.Defaults defaults) {
        return new RoutingConfiguration(channels, providers, policies, defaults);
    }

    public Optional<ChannelConfig> channelConfig(Channel channel) {
        return Optional.ofNullable(channels.get(channel));
    }

    /** Providers serving the channel, in configuration order. */
    public List<Provider> providersFor(Channel channel) {
        return providers.stream()
                .filter(provider -> provider.channel() == channel)
                .toList();
    }

    public Optional<Provider> provider(ProviderId providerId) {
        return providers.stream()
                .filter(provider -> provider.id().equals(providerId))
                .findFirst();
    }

    public Optional<Provider> provider(ProviderCode providerCode) {
        return providers.stream()
                .filter(provider -> provider.code().equals(providerCode))
                .findFirst();
    }

    /** Highest-priority enabled policy matching the message attributes; the first match wins (FR-8.9). */
    public Optional<RoutingPolicy> matchingPolicy(
            StreamId streamId, TrafficClass trafficClass, Priority priority, Channel channel) {
        return policies.stream()
                .filter(policy -> policy.matches(streamId, trafficClass, priority, channel))
                .max(Comparator.comparingInt(RoutingPolicy::priority));
    }
}
