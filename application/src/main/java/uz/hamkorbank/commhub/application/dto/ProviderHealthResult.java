package uz.hamkorbank.commhub.application.dto;

import java.util.List;
import uz.hamkorbank.commhub.domain.model.type.ProviderHealthStatus;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Outcome of one pass of the provider health monitor (FR-6.3, PR-02, OBS-04).
 *
 * @param checked providers looked at in this pass
 * @param transitions the ones whose status actually changed; each is worth an alert
 */
public record ProviderHealthResult(int checked, List<Transition> transitions) {

    public ProviderHealthResult {
        Guard.notNegative(checked, "ProviderHealthResult.checked");
        transitions = Guard.copyOf(transitions);
    }

    public static ProviderHealthResult none() {
        return new ProviderHealthResult(0, List.of());
    }

    /** Whether any provider changed health, i.e. whether routing behaviour changed with it. */
    public boolean hasTransitions() {
        return !transitions.isEmpty();
    }

    /**
     * One provider that changed health (FR-6.3).
     *
     * @param detail figures behind the decision, for the operator and the audit log
     */
    public record Transition(ProviderCode provider, ProviderHealthStatus from, ProviderHealthStatus to, String detail) {

        public Transition {
            Guard.notNull(provider, "Transition.provider");
            Guard.notNull(from, "Transition.from");
            Guard.notNull(to, "Transition.to");
        }

        /** Whether the provider left routing; a failover happened on the next message (FR-2.2). */
        public boolean isFailover() {
            return to == ProviderHealthStatus.DOWN;
        }

        /** Whether the provider came back into routing (FR-6.3). */
        public boolean isFailback() {
            return from == ProviderHealthStatus.DOWN && to != ProviderHealthStatus.DOWN;
        }
    }
}
