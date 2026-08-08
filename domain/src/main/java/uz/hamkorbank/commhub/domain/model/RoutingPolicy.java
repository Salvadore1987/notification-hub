package uz.hamkorbank.commhub.domain.model;

import java.util.List;
import java.util.Optional;
import uz.hamkorbank.commhub.domain.model.type.BalancingStrategy;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.Priority;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.model.vo.RoutingPolicyId;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Declarative routing rule stored in the database and applied without a restart (§6.1, §10.1
 * {@code routing_policy}, AD-07, FR-8.9).
 *
 * <p>{@link Match} selects the messages a rule applies to, {@link Action} states the channel, the
 * provider order and the balancing strategy to use. Rules are evaluated by descending
 * {@link #priority()}; the first match wins.
 */
public final class RoutingPolicy extends AggregateRoot<RoutingPolicyId> {

    private final Match match;
    private final Action action;
    private final int priority;
    private boolean enabled;

    private RoutingPolicy(RoutingPolicyId id, Match match, Action action, int priority, boolean enabled) {
        super(id);
        this.match = Guard.notNull(match, "RoutingPolicy.match");
        this.action = Guard.notNull(action, "RoutingPolicy.action");
        this.priority = Guard.notNegative(priority, "RoutingPolicy.priority");
        this.enabled = enabled;
    }

    public static RoutingPolicy of(RoutingPolicyId id, Match match, Action action, int priority) {
        return new RoutingPolicy(id, match, action, priority, true);
    }

    /** Whether the rule applies to a message with these envelope attributes. */
    public boolean matches(StreamId streamId, TrafficClass trafficClass, Priority messagePriority, Channel channel) {
        return enabled && match.matches(streamId, trafficClass, messagePriority, channel);
    }

    public void enable() {
        this.enabled = true;
    }

    public void disable() {
        this.enabled = false;
    }

    public Match match() {
        return match;
    }

    public Action action() {
        return action;
    }

    public int priority() {
        return priority;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Match part of a rule; a {@code null} field means "any" (§10.1 {@code routing_policy.match}).
     *
     * @param minPriority matches messages with at least this priority
     */
    public record Match(StreamId streamId, TrafficClass trafficClass, Priority minPriority, Channel channel) {

        public static Match any() {
            return new Match(null, null, null, null);
        }

        public static Match ofStream(StreamId streamId) {
            return new Match(streamId, null, null, null);
        }

        public static Match ofTrafficClass(TrafficClass trafficClass) {
            return new Match(null, trafficClass, null, null);
        }

        public boolean matches(
                StreamId candidateStream,
                TrafficClass candidateClass,
                Priority candidatePriority,
                Channel candidateChannel) {
            if (streamId != null && !streamId.equals(candidateStream)) {
                return false;
            }
            if (trafficClass != null && trafficClass != candidateClass) {
                return false;
            }
            if (minPriority != null && (candidatePriority == null || !candidatePriority.isAtLeast(minPriority))) {
                return false;
            }
            return channel == null || channel == candidateChannel;
        }
    }

    /**
     * Action part of a rule (§10.1 {@code routing_policy.action}).
     *
     * @param channel channel to use; {@code null} keeps the channel chosen by the plan/stream default
     * @param providerOrder explicit provider preference; empty falls back to the channel configuration
     * @param balancingStrategy strategy override; {@code null} keeps the channel strategy (FR-2.3)
     */
    public record Action(Channel channel, List<ProviderCode> providerOrder, BalancingStrategy balancingStrategy) {

        public Action {
            providerOrder = Guard.copyOf(providerOrder);
        }

        public static Action toChannel(Channel channel) {
            return new Action(channel, List.of(), null);
        }

        public static Action toProviders(Channel channel, List<ProviderCode> providerOrder) {
            return new Action(channel, providerOrder, null);
        }

        public Optional<Channel> channelOptional() {
            return Optional.ofNullable(channel);
        }

        public Optional<BalancingStrategy> balancingStrategyOptional() {
            return Optional.ofNullable(balancingStrategy);
        }
    }
}
