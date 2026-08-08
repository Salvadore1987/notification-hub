package uz.hamkorbank.commhub.application.port.in.command;

import uz.hamkorbank.commhub.domain.model.Actor;
import uz.hamkorbank.commhub.domain.model.RoutingPolicy;
import uz.hamkorbank.commhub.domain.model.vo.RoutingPolicyId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Creation or replacement of one declarative routing rule (FR-8.9, AD-07).
 *
 * <p>Replacement rather than patching: a rule is a small, whole statement — "traffic of this stream on
 * this class goes to these providers" — and editing half of it is how a routing table starts
 * contradicting itself.
 *
 * @param policyId {@code null} creates a rule, a value replaces the rule with that id
 */
public record SaveRoutingPolicyCommand(
        Actor actor, RoutingPolicyId policyId, RoutingPolicy.Match match, RoutingPolicy.Action action, int priority) {

    public SaveRoutingPolicyCommand {
        Guard.notNull(actor, "SaveRoutingPolicyCommand.actor");
        Guard.notNull(match, "SaveRoutingPolicyCommand.match");
        Guard.notNull(action, "SaveRoutingPolicyCommand.action");
        Guard.notNegative(priority, "SaveRoutingPolicyCommand.priority");
    }
}
