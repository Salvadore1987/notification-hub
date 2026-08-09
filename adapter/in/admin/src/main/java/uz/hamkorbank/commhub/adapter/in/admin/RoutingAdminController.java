package uz.hamkorbank.commhub.adapter.in.admin;

import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.hamkorbank.commhub.adapter.in.admin.dto.RouteEvaluationRequest;
import uz.hamkorbank.commhub.adapter.in.admin.dto.RouteEvaluationResponse;
import uz.hamkorbank.commhub.adapter.in.admin.dto.RoutingPolicyRequest;
import uz.hamkorbank.commhub.adapter.in.admin.dto.RoutingPolicyResponse;
import uz.hamkorbank.commhub.adapter.in.admin.mapper.AdminCommandMapper;
import uz.hamkorbank.commhub.adapter.in.admin.mapper.AdminViewMapper;
import uz.hamkorbank.commhub.adapter.in.admin.support.AdminAuthority;
import uz.hamkorbank.commhub.adapter.in.admin.support.AdminValues;
import uz.hamkorbank.commhub.adapter.in.rest.security.AuthenticatedCaller;
import uz.hamkorbank.commhub.application.port.in.EvaluateRoute;
import uz.hamkorbank.commhub.application.port.in.GetRoutingConfiguration;
import uz.hamkorbank.commhub.application.port.in.ManageRoutingPolicies;
import uz.hamkorbank.commhub.application.port.in.command.RoutingPolicyStateCommand;
import uz.hamkorbank.commhub.domain.model.vo.RoutingPolicyId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Routing policies and the dry run (§11.2 "Маршрутизация", FR-2.2, ADMIN only).
 *
 * <p>{@code POST /evaluate} is the screen's most valuable endpoint and the reason the section exists:
 * it answers "which route would this message get" by building a message in memory and running the
 * <em>real</em> router against the <em>real</em> configuration. Nothing is sent and nothing is stored,
 * and there is deliberately no second implementation of the routing rules to disagree with the first.
 */
@RestController
@RequestMapping(AdminApi.ROUTING)
public class RoutingAdminController {

    private final GetRoutingConfiguration configuration;
    private final ManageRoutingPolicies managePolicies;
    private final EvaluateRoute evaluateRoute;
    private final AuthenticatedCaller caller;
    private final AdminViewMapper viewMapper;
    private final AdminCommandMapper commandMapper;

    public RoutingAdminController(
            GetRoutingConfiguration configuration,
            ManageRoutingPolicies managePolicies,
            EvaluateRoute evaluateRoute,
            AuthenticatedCaller caller,
            AdminViewMapper viewMapper,
            AdminCommandMapper commandMapper) {
        this.configuration = Guard.notNull(configuration, "configuration");
        this.managePolicies = Guard.notNull(managePolicies, "managePolicies");
        this.evaluateRoute = Guard.notNull(evaluateRoute, "evaluateRoute");
        this.caller = Guard.notNull(caller, "caller");
        this.viewMapper = Guard.notNull(viewMapper, "viewMapper");
        this.commandMapper = Guard.notNull(commandMapper, "commandMapper");
    }

    /** {@code GET /api/admin/v1/routing/policies} — every policy, in the order they are evaluated. */
    @GetMapping(path = "/policies", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(AdminAuthority.ADMIN)
    public List<RoutingPolicyResponse> policies() {
        return configuration.policies().stream()
                .map(viewMapper::toRoutingPolicy)
                .toList();
    }

    /** {@code POST /api/admin/v1/routing/policies} — creates a policy (FR-2.2). */
    @PostMapping(
            path = "/policies",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(AdminAuthority.ADMIN)
    public RoutingPolicyResponse create(@RequestBody RoutingPolicyRequest request) {
        return viewMapper.toRoutingPolicy(
                managePolicies.save(commandMapper.toSaveRoutingPolicy(null, request, caller.actor())));
    }

    /** {@code PUT /api/admin/v1/routing/policies/{policyId}} — replaces one whole policy (FR-2.2). */
    @PutMapping(
            path = "/policies/{policyId}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(AdminAuthority.ADMIN)
    public RoutingPolicyResponse replace(@PathVariable String policyId, @RequestBody RoutingPolicyRequest request) {
        return viewMapper.toRoutingPolicy(
                managePolicies.save(commandMapper.toSaveRoutingPolicy(policyId, request, caller.actor())));
    }

    /**
     * {@code POST /api/admin/v1/routing/policies/{policyId}/state/{enabled}} — switches a policy off.
     *
     * <p>Its own endpoint rather than a field, because switching a policy off is how a routing change is
     * rolled back in a hurry: it has to be one click that does not require re-sending a body somebody
     * might have edited in the meantime.
     */
    @PostMapping(path = "/policies/{policyId}/state/{enabled}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(AdminAuthority.ADMIN)
    public RoutingPolicyResponse changeState(@PathVariable String policyId, @PathVariable boolean enabled) {
        return viewMapper.toRoutingPolicy(
                managePolicies.changeState(new RoutingPolicyStateCommand(caller.actor(), policyId(policyId), enabled)));
    }

    /** {@code DELETE /api/admin/v1/routing/policies/{policyId}} — removes a policy (FR-2.2). */
    @DeleteMapping(path = "/policies/{policyId}")
    @PreAuthorize(AdminAuthority.ADMIN)
    public ResponseEntity<Void> delete(@PathVariable String policyId) {
        managePolicies.delete(new RoutingPolicyStateCommand(caller.actor(), policyId(policyId), false));
        return ResponseEntity.noContent().build();
    }

    /** {@code POST /api/admin/v1/routing/evaluate} — dry run of one hypothetical message (§11.2). */
    @PostMapping(
            path = "/evaluate",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(AdminAuthority.ADMIN)
    public RouteEvaluationResponse evaluate(@RequestBody RouteEvaluationRequest request) {
        return viewMapper.toRouteEvaluation(evaluateRoute.evaluate(commandMapper.toRouteEvaluation(request)));
    }

    private static RoutingPolicyId policyId(String raw) {
        return RoutingPolicyId.of(AdminValues.requiredUuid(raw, "policyId"));
    }
}
