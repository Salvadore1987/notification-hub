package uz.hamkorbank.commhub.application.port.in.command;

import uz.hamkorbank.commhub.domain.model.Actor;
import uz.hamkorbank.commhub.domain.model.vo.RoutingPolicyId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Enabling, disabling or deleting one routing rule (FR-8.9, AD-07).
 *
 * <p>Disabling is the reversible operation an operator reaches for during an incident; deletion exists
 * for rules that were a mistake.
 */
public record RoutingPolicyStateCommand(Actor actor, RoutingPolicyId policyId, boolean enabled) {

    public RoutingPolicyStateCommand {
        Guard.notNull(actor, "RoutingPolicyStateCommand.actor");
        Guard.notNull(policyId, "RoutingPolicyStateCommand.policyId");
    }
}
