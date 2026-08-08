package uz.hamkorbank.commhub.adapter.in.admin.dto;

import java.util.List;

/**
 * A routing policy as the administration screen shows it (§11.2 "Маршрутизация", FR-2.2).
 *
 * @param priority lower runs first; the whole point of the screen is that the order is visible
 */
public record RoutingPolicyResponse(String policyId, MatchDto match, ActionDto action, int priority, boolean enabled) {

    /** What a message has to look like for the policy to apply; every field is optional. */
    public record MatchDto(String streamId, String trafficClass, String minPriority, String channel) {}

    /** What the policy then does with it. */
    public record ActionDto(String channel, List<String> providerOrder, String balancingStrategy) {}
}
