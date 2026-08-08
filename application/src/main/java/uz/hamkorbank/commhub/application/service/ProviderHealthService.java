package uz.hamkorbank.commhub.application.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.hamkorbank.commhub.application.dto.ProviderHealthResult;
import uz.hamkorbank.commhub.application.policy.ProviderHealthPolicy;
import uz.hamkorbank.commhub.application.port.in.CheckProviderHealth;
import uz.hamkorbank.commhub.application.port.in.command.CheckProviderHealthCommand;
import uz.hamkorbank.commhub.application.port.out.ClockPort;
import uz.hamkorbank.commhub.application.port.out.ProviderConfigRepository;
import uz.hamkorbank.commhub.application.port.out.ProviderDeliveryStats;
import uz.hamkorbank.commhub.application.port.out.ProviderStatsPort;
import uz.hamkorbank.commhub.application.port.out.provider.ProviderProbePort;
import uz.hamkorbank.commhub.domain.model.Provider;
import uz.hamkorbank.commhub.domain.model.type.ProviderHealthStatus;
import uz.hamkorbank.commhub.domain.model.vo.ProviderId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Proactive detection of provider degradation, with automatic failover and failback (FR-6.3, PR-02).
 *
 * <p>One pass reads the delivery figures of the window, asks {@link ProviderHealthPolicy} what status
 * each provider should carry, and writes back the ones that changed. Nothing else happens: the failover
 * itself is a consequence, because {@code Provider.isSelectable()} already excludes a {@code DOWN}
 * provider and the router picks the reserve on the very next message (FR-2.2).
 *
 * <p>A synthetic probe is used when one is deployed for the provider's adapter, and its verdict
 * overrides the passive figures in one direction only — a provider that fails its probe is
 * {@code DOWN} even if the last few messages went out, because the probe tests the integration while
 * the figures describe the past. In the other direction the figures win: a passing probe does not
 * prove that real traffic is being delivered. No SMS adapter of the MVP has a probe (§9.1, §9.2).
 *
 * <p>Disabled providers are skipped — their health is not a fact about anything — but providers in
 * maintenance are still measured, so that an operator taking one out of maintenance knows what they
 * are turning back on.
 */
@Service
public class ProviderHealthService implements CheckProviderHealth {

    private final ProviderConfigRepository providers;
    private final ProviderStatsPort stats;
    private final ObjectProvider<ProviderProbePort> probes;
    private final ProviderHealthPolicy policy;
    private final ClockPort clock;

    public ProviderHealthService(
            ProviderConfigRepository providers,
            ProviderStatsPort stats,
            ObjectProvider<ProviderProbePort> probes,
            ProviderHealthPolicy policy,
            ClockPort clock) {
        this.providers = Guard.notNull(providers, "providers");
        this.stats = Guard.notNull(stats, "stats");
        this.probes = Guard.notNull(probes, "probes");
        this.policy = Guard.notNull(policy, "policy");
        this.clock = Guard.notNull(clock, "clock");
    }

    @Override
    @Transactional
    public ProviderHealthResult check(CheckProviderHealthCommand command) {
        Guard.notNull(command, "command");
        Instant now = clock.now();
        Instant from = now.minus(policy.window());
        List<Provider> candidates = candidates(command);
        if (candidates.isEmpty()) {
            return ProviderHealthResult.none();
        }
        Map<ProviderId, ProviderDeliveryStats> figures = figures(from, now);
        List<ProviderHealthResult.Transition> transitions = new ArrayList<>();
        for (Provider provider : candidates) {
            ProviderDeliveryStats providerStats =
                    figures.getOrDefault(provider.id(), ProviderDeliveryStats.none(provider.id()));
            evaluate(provider, providerStats, now).ifPresent(transitions::add);
        }
        return new ProviderHealthResult(candidates.size(), transitions);
    }

    /**
     * Applies the verdict to one provider; empty when its status did not change.
     *
     * <p>Nothing is written when the status stays the same, and that is load-bearing rather than an
     * optimisation: {@code health_checked_at} records when the current status was <em>established</em>,
     * and it is what the probation of {@link ProviderHealthPolicy} measures against. Stamping it on every
     * pass would keep resetting that clock, and a {@code DOWN} provider — which receives no traffic and
     * can therefore never produce figures that clear it — would stay down forever (FR-6.3).
     */
    private Optional<ProviderHealthResult.Transition> evaluate(
            Provider provider, ProviderDeliveryStats providerStats, Instant now) {
        ProviderHealthStatus current = provider.health();
        Duration sinceStatusChange = provider.healthCheckedAt()
                .map(establishedAt -> Duration.between(establishedAt, now))
                .orElse(policy.window());
        ProviderHealthStatus target = probe(provider)
                .filter(healthy -> !healthy)
                .map(ignored -> ProviderHealthStatus.DOWN)
                .orElseGet(() -> policy.evaluate(providerStats, current, sinceStatusChange));
        if (target == current) {
            return Optional.empty();
        }
        String detail = describe(providerStats);
        providers.updateHealth(provider.id(), target, detail, now);
        return Optional.of(new ProviderHealthResult.Transition(provider.code(), current, target, detail));
    }

    /** Verdict of a deployed probe, or empty when the adapter has none (PR-02). */
    private Optional<Boolean> probe(Provider provider) {
        return probes.stream()
                .filter(candidate -> candidate.supports(provider.ref()))
                .findFirst()
                .map(candidate -> candidate.probe(provider.ref()).healthy());
    }

    private List<Provider> candidates(CheckProviderHealthCommand command) {
        List<Provider> all =
                command.channelOptional().map(providers::findProviders).orElseGet(providers::findAllProviders);
        return all.stream().filter(Provider::isEnabled).toList();
    }

    private Map<ProviderId, ProviderDeliveryStats> figures(Instant from, Instant to) {
        Map<ProviderId, ProviderDeliveryStats> byProvider = new HashMap<>();
        stats.statsSince(from, to).forEach(entry -> byProvider.put(entry.providerId(), entry));
        return byProvider;
    }

    private static String describe(ProviderDeliveryStats providerStats) {
        if (providerStats.isIdle()) {
            return "no attempts in the window";
        }
        return "attempts=%d, errorRate=%.2f, timeoutRate=%.2f, avgLatencyMs=%.0f"
                .formatted(
                        providerStats.attempts(),
                        providerStats.errorRate(),
                        providerStats.timeoutRate(),
                        providerStats.averageLatencyMillis());
    }
}
