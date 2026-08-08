package uz.hamkorbank.commhub.application.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.hamkorbank.commhub.application.dto.RoutingPolicyView;
import uz.hamkorbank.commhub.application.exception.NotFoundException;
import uz.hamkorbank.commhub.application.mapper.ConfigMapper;
import uz.hamkorbank.commhub.application.port.in.ManageRoutingPolicies;
import uz.hamkorbank.commhub.application.port.in.command.RoutingPolicyStateCommand;
import uz.hamkorbank.commhub.application.port.in.command.SaveRoutingPolicyCommand;
import uz.hamkorbank.commhub.application.port.out.ProviderConfigRepository;
import uz.hamkorbank.commhub.application.service.support.ConfigAuditor;
import uz.hamkorbank.commhub.domain.model.RoutingPolicy;
import uz.hamkorbank.commhub.domain.model.vo.RoutingPolicyId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Declarative routing rules (FR-8.9, AD-07).
 *
 * <p>A rule is stored whole: {@code match} decides which messages it applies to, {@code action} states
 * the channel, the provider preference and the balancing strategy, and {@code priority} orders it
 * against the others. The {@code Router} evaluates them by descending priority and stops at the first
 * match, so priority is the only thing that resolves an overlap — which is why it is required rather
 * than defaulted.
 */
@Service
public class RoutingPolicyConfigService implements ManageRoutingPolicies {

    private static final String ENTITY = "routing_policy";

    private final ProviderConfigRepository configuration;
    private final ConfigMapper mapper;
    private final ConfigAuditor auditor;

    public RoutingPolicyConfigService(
            ProviderConfigRepository configuration, ConfigMapper mapper, ConfigAuditor auditor) {
        this.configuration = Guard.notNull(configuration, "configuration");
        this.mapper = Guard.notNull(mapper, "mapper");
        this.auditor = Guard.notNull(auditor, "auditor");
    }

    @Override
    @Transactional
    public RoutingPolicyView save(SaveRoutingPolicyCommand command) {
        Guard.notNull(command, "command");
        RoutingPolicyId policyId = command.policyId() == null ? RoutingPolicyId.newId() : command.policyId();
        String before = configuration
                .findPolicy(policyId)
                .map(RoutingPolicyConfigService::describe)
                .orElse(null);
        RoutingPolicy policy = RoutingPolicy.of(policyId, command.match(), command.action(), command.priority());
        configuration.save(policy);
        auditor.record(
                command.actor(),
                before == null ? "routing_policy.create" : "routing_policy.update",
                ENTITY,
                policyId.value().toString(),
                before,
                describe(policy));
        return mapper.toView(policy);
    }

    @Override
    @Transactional
    public RoutingPolicyView changeState(RoutingPolicyStateCommand command) {
        Guard.notNull(command, "command");
        RoutingPolicy policy = require(command.policyId());
        String before = describe(policy);
        if (command.enabled()) {
            policy.enable();
        } else {
            policy.disable();
        }
        configuration.save(policy);
        auditor.record(
                command.actor(),
                "routing_policy.state",
                ENTITY,
                policy.id().value().toString(),
                before,
                describe(policy));
        return mapper.toView(policy);
    }

    @Override
    @Transactional
    public void delete(RoutingPolicyStateCommand command) {
        Guard.notNull(command, "command");
        RoutingPolicy policy = require(command.policyId());
        configuration.deletePolicy(policy.id());
        auditor.record(
                command.actor(),
                "routing_policy.delete",
                ENTITY,
                policy.id().value().toString(),
                describe(policy),
                null);
    }

    private RoutingPolicy require(RoutingPolicyId policyId) {
        return configuration.findPolicy(policyId).orElseThrow(() -> NotFoundException.of(ENTITY, policyId.value()));
    }

    private static String describe(RoutingPolicy policy) {
        return "priority=%d, enabled=%s, match=%s, action=%s"
                .formatted(policy.priority(), policy.isEnabled(), policy.match(), policy.action());
    }
}
