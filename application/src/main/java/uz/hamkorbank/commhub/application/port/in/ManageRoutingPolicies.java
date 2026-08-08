package uz.hamkorbank.commhub.application.port.in;

import uz.hamkorbank.commhub.application.dto.RoutingPolicyView;
import uz.hamkorbank.commhub.application.port.in.command.RoutingPolicyStateCommand;
import uz.hamkorbank.commhub.application.port.in.command.SaveRoutingPolicyCommand;

/**
 * Administration of the declarative routing rules (FR-8.9, AD-07).
 *
 * <p>The MVP's answer to "a rule engine without code changes": rules are match/action rows in
 * PostgreSQL, evaluated by the domain {@code Router} and applied without a restart. A real engine with
 * expressions is stage 2 of the SRS.
 */
public interface ManageRoutingPolicies {

    RoutingPolicyView save(SaveRoutingPolicyCommand command);

    RoutingPolicyView changeState(RoutingPolicyStateCommand command);

    void delete(RoutingPolicyStateCommand command);
}
