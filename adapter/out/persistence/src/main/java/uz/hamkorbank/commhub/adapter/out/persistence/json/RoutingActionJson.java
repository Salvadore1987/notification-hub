package uz.hamkorbank.commhub.adapter.out.persistence.json;

import java.util.List;
import uz.hamkorbank.commhub.domain.model.RoutingPolicy;
import uz.hamkorbank.commhub.domain.model.type.BalancingStrategy;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;

/** {@link RoutingPolicy.Action} inside {@code routing_policy.action}: what a matched message gets. */
public record RoutingActionJson(String channel, List<String> providerOrder, String balancingStrategy) {

    public static RoutingActionJson of(RoutingPolicy.Action action) {
        return new RoutingActionJson(
                action.channel() == null ? null : action.channel().name(),
                action.providerOrder().stream().map(ProviderCode::value).toList(),
                action.balancingStrategy() == null
                        ? null
                        : action.balancingStrategy().name());
    }

    public RoutingPolicy.Action toDomain() {
        return new RoutingPolicy.Action(
                channel == null ? null : Channel.valueOf(channel),
                providerOrder == null
                        ? List.of()
                        : providerOrder.stream().map(ProviderCode::of).toList(),
                balancingStrategy == null ? null : BalancingStrategy.valueOf(balancingStrategy));
    }
}
