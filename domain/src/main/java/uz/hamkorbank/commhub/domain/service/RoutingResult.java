package uz.hamkorbank.commhub.domain.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import uz.hamkorbank.commhub.domain.model.type.BalancingStrategy;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.RejectionReason;
import uz.hamkorbank.commhub.domain.model.vo.ProviderRef;
import uz.hamkorbank.commhub.domain.support.Guard;

/** Outcome of a routing decision: either a route or the reason why there is none (MP-05, FR-2.2). */
public sealed interface RoutingResult {

    /** Whether a route was found. */
    default boolean isRouted() {
        return this instanceof Routed;
    }

    /** The decision, or empty when no route is available. */
    default Optional<Routed> routed() {
        return this instanceof Routed decision ? Optional.of(decision) : Optional.empty();
    }

    /**
     * A usable route (MP-05).
     *
     * @param channel channel the message will be delivered over
     * @param provider provider chosen by the balancing strategy
     * @param fallbackProviders remaining providers in fallback order (FR-2.2)
     * @param strategy strategy that produced the choice (FR-2.3)
     */
    record Routed(
            Channel channel, ProviderRef provider, List<ProviderRef> fallbackProviders, BalancingStrategy strategy)
            implements RoutingResult {

        public Routed {
            Guard.notNull(channel, "Routed.channel");
            Guard.notNull(provider, "Routed.provider");
            Guard.notNull(strategy, "Routed.strategy");
            fallbackProviders = Guard.copyOf(fallbackProviders);
            Guard.isTrue(
                    provider.channel() == channel,
                    "provider %s does not serve channel %s".formatted(provider.code(), channel));
            Guard.isTrue(!fallbackProviders.contains(provider), "the chosen provider must not repeat in the fallbacks");
        }

        public boolean hasFallback() {
            return !fallbackProviders.isEmpty();
        }

        /** All providers to try, primary first (FR-2.2). */
        public List<ProviderRef> attemptOrder() {
            List<ProviderRef> order = new ArrayList<>(fallbackProviders.size() + 1);
            order.add(provider);
            order.addAll(fallbackProviders);
            return List.copyOf(order);
        }
    }

    /**
     * No route is available; the message is rejected with this reason (FR-2.2, IR-01).
     *
     * @param reason canonical rejection reason
     * @param detail human-readable explanation for the operator
     */
    record NoRoute(RejectionReason reason, String detail) implements RoutingResult {

        public NoRoute {
            Guard.notNull(reason, "NoRoute.reason");
        }

        public static NoRoute of(String detail) {
            return new NoRoute(RejectionReason.NO_ROUTE_AVAILABLE, detail);
        }
    }
}
