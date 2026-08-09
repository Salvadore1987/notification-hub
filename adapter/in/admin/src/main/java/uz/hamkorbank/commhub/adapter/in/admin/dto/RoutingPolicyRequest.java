package uz.hamkorbank.commhub.adapter.in.admin.dto;

/**
 * Body of creating or replacing a routing policy (§11.2 "Маршрутизация", FR-2.2).
 *
 * <p>Whole-policy replacement rather than a patch, because a policy is a rule and half a rule is not a
 * rule: an edit that changed the match without the action would leave a policy nobody wrote.
 */
public record RoutingPolicyRequest(
        RoutingPolicyResponse.MatchDto match, RoutingPolicyResponse.ActionDto action, Integer priority) {

    /** Policies without an explicit priority run last, which is the safe end of the list. */
    public static final int DEFAULT_PRIORITY = 100;

    public RoutingPolicyRequest {
        priority = priority == null ? DEFAULT_PRIORITY : priority;
    }
}
