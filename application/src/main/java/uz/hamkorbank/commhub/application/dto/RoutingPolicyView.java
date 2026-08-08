package uz.hamkorbank.commhub.application.dto;

import uz.hamkorbank.commhub.domain.model.RoutingPolicy;
import uz.hamkorbank.commhub.domain.model.vo.RoutingPolicyId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * One declarative routing rule for the administration screens (FR-8.9).
 *
 * @param priority rules are evaluated by descending priority; the first match wins
 */
public record RoutingPolicyView(
        RoutingPolicyId policyId,
        RoutingPolicy.Match match,
        RoutingPolicy.Action action,
        int priority,
        boolean enabled) {

    public RoutingPolicyView {
        Guard.notNull(policyId, "RoutingPolicyView.policyId");
        Guard.notNull(match, "RoutingPolicyView.match");
        Guard.notNull(action, "RoutingPolicyView.action");
    }
}
