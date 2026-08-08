package uz.hamkorbank.commhub.domain.service;

import java.util.List;
import java.util.Optional;
import uz.hamkorbank.commhub.domain.model.ChannelConfig;
import uz.hamkorbank.commhub.domain.model.Message;
import uz.hamkorbank.commhub.domain.model.Provider;
import uz.hamkorbank.commhub.domain.model.RoutingPolicy;
import uz.hamkorbank.commhub.domain.model.type.BalancingStrategy;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.RejectionReason;
import uz.hamkorbank.commhub.domain.model.vo.Money;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.model.vo.ProviderRef;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Chooses the channel and the provider for a message (MP-05, FR-2.2, FR-2.3, FR-2.4).
 *
 * <p>The router knows nothing about concrete providers: it returns a {@link ProviderRef}, and the
 * application layer resolves the channel output port implementation from it (MP-05, AR-04).
 *
 * <p>Channel selection order: a matching routing policy, then the channel plan of the message, then the
 * stream default, then the first channel the recipient is reachable on (FR-2.4, MP-03). Provider
 * selection applies the balancing strategy of the policy or of the channel (FR-2.3) over the fallback
 * order, skipping providers that are disabled, in maintenance, reported {@code DOWN} or already tried
 * (FR-2.7, FR-6.3).
 *
 * <p>Stateless: the round-robin position comes in with {@link RoutingRequest#rotation()}, so one
 * instance serves every virtual thread (AR-07).
 */
public final class Router {

    private final FallbackChain fallbackChain;

    public Router(FallbackChain fallbackChain) {
        this.fallbackChain = Guard.notNull(fallbackChain, "fallbackChain");
    }

    /** Routes a message against a configuration snapshot. */
    public RoutingResult route(RoutingRequest request, RoutingConfiguration configuration) {
        Guard.notNull(request, "request");
        Guard.notNull(configuration, "configuration");
        Message message = request.message();
        Optional<RoutingPolicy> policy = configuration.matchingPolicy(
                message.envelope().streamId(),
                message.envelope().trafficClass(),
                message.envelope().priority(),
                null);

        Optional<Channel> channel = selectChannel(message, configuration, policy);
        if (channel.isEmpty()) {
            return RoutingResult.NoRoute.of("recipient has no usable address for the planned channels");
        }
        Optional<ChannelConfig> channelConfig = configuration.channelConfig(channel.get());
        if (channelConfig.isEmpty()) {
            return RoutingResult.NoRoute.of("channel %s is not configured".formatted(channel.get()));
        }
        if (!channelConfig.get().status().acceptsTraffic()) {
            return new RoutingResult.NoRoute(
                    RejectionReason.NO_ROUTE_AVAILABLE,
                    "channel %s is %s"
                            .formatted(channel.get(), channelConfig.get().status()));
        }

        List<ProviderRef> candidates = candidates(channelConfig.get(), configuration, request, policy);
        if (candidates.isEmpty()) {
            return RoutingResult.NoRoute.of("no selectable provider for channel " + channel.get());
        }
        BalancingStrategy strategy = policy.flatMap(matched -> matched.action().balancingStrategyOptional())
                .orElseGet(() -> channelConfig.get().balancingStrategy());
        ProviderRef primary = select(strategy, candidates, configuration, request);
        List<ProviderRef> fallbacks = candidates.stream()
                .filter(candidate -> !candidate.equals(primary))
                .toList();
        return new RoutingResult.Routed(channel.get(), primary, fallbacks, strategy);
    }

    /** Channel a message will be delivered over (FR-2.4, MP-03). */
    private Optional<Channel> selectChannel(
            Message message, RoutingConfiguration configuration, Optional<RoutingPolicy> policy) {
        List<Channel> deliverable = message.deliverableChannels();
        if (deliverable.isEmpty()) {
            return Optional.empty();
        }
        Optional<Channel> fromPolicy =
                policy.flatMap(matched -> matched.action().channelOptional()).filter(deliverable::contains);
        if (fromPolicy.isPresent()) {
            return fromPolicy;
        }
        Optional<Channel> fromPlan = message.channelPlan().primaryChannel().filter(deliverable::contains);
        if (fromPlan.isPresent()) {
            return fromPlan;
        }
        Optional<Channel> fromStreamDefaults =
                configuration.streamDefaults().channelOptional().filter(deliverable::contains);
        return fromStreamDefaults.isPresent() ? fromStreamDefaults : Optional.of(deliverable.getFirst());
    }

    /** Selectable providers in preference order, narrowed by the policy and by previous attempts. */
    private List<ProviderRef> candidates(
            ChannelConfig channelConfig,
            RoutingConfiguration configuration,
            RoutingRequest request,
            Optional<RoutingPolicy> policy) {
        List<ProviderRef> configured =
                fallbackChain.providerChain(channelConfig, configuration.providersFor(channelConfig.channel()));
        List<ProviderCode> preferred =
                policy.map(matched -> matched.action().providerOrder()).orElse(List.of());
        List<ProviderRef> ordered = preferred.isEmpty()
                ? configured
                : preferred.stream()
                        .flatMap(code ->
                                configured.stream().filter(ref -> ref.code().equals(code)))
                        .toList();
        return ordered.stream()
                .filter(ref -> !request.excludedProviders().contains(ref))
                .toList();
    }

    private static ProviderRef select(
            BalancingStrategy strategy,
            List<ProviderRef> candidates,
            RoutingConfiguration configuration,
            RoutingRequest request) {
        return switch (strategy) {
            case ROUND_ROBIN -> candidates.get(Math.floorMod(request.rotation(), candidates.size()));
            case WEIGHTED -> selectWeighted(candidates, configuration, request.rotation());
            case LEAST_COST -> selectCheapest(candidates, configuration, request.segments());
            case PRIMARY_ONLY -> candidates.getFirst();
            default -> candidates.getFirst();
        };
    }

    /** Weighted round-robin over the provider weights (FR-2.3). */
    private static ProviderRef selectWeighted(
            List<ProviderRef> candidates, RoutingConfiguration configuration, long rotation) {
        int totalWeight = candidates.stream()
                .mapToInt(candidate -> weightOf(candidate, configuration))
                .sum();
        if (totalWeight <= 0) {
            return candidates.getFirst();
        }
        long target = Math.floorMod(rotation, totalWeight);
        long cumulative = 0L;
        for (ProviderRef candidate : candidates) {
            cumulative += weightOf(candidate, configuration);
            if (target < cumulative) {
                return candidate;
            }
        }
        return candidates.getLast();
    }

    /**
     * Least-cost routing over the actual segment count (FR-2.3, §18.3).
     *
     * <p>Providers without a tariff, and providers billing in another currency than the cheapest
     * candidate found so far, are skipped; the fallback order decides ties.
     */
    private static ProviderRef selectCheapest(
            List<ProviderRef> candidates, RoutingConfiguration configuration, int segments) {
        ProviderRef cheapest = null;
        Money cheapestCost = null;
        for (ProviderRef candidate : candidates) {
            Optional<Money> cost =
                    configuration.provider(candidate.id()).flatMap(provider -> provider.costOf(segments));
            if (cost.isEmpty()) {
                continue;
            }
            if (cheapestCost != null && !cost.get().currency().equals(cheapestCost.currency())) {
                continue;
            }
            if (cheapestCost == null || cost.get().compareTo(cheapestCost) < 0) {
                cheapest = candidate;
                cheapestCost = cost.get();
            }
        }
        return cheapest == null ? candidates.getFirst() : cheapest;
    }

    private static int weightOf(ProviderRef ref, RoutingConfiguration configuration) {
        return configuration.provider(ref.id()).map(Provider::weight).orElse(Provider.DEFAULT_WEIGHT);
    }
}
