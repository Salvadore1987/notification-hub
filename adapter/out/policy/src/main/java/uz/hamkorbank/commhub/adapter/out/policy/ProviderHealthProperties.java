package uz.hamkorbank.commhub.adapter.out.policy;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import uz.hamkorbank.commhub.application.policy.ProviderHealthPolicy;

/**
 * Thresholds of the passive provider health monitor (FR-6.3, PR-02).
 *
 * <p>Shares its prefix with the cadence of {@code ProviderHealthScheduler}
 * ({@code commhub.provider.health.enabled/interval/initial-delay}), which is read straight from the
 * placeholders of {@code @Scheduled} — one screen of yaml describes one mechanism. Unknown keys are
 * ignored by constructor binding, so the two coexist without either having to know about the other.
 *
 * @param window how far back the delivery figures are read
 * @param minimumAttempts attempts below which a rate is noise and the status is left alone
 * @param degradedErrorRate error share from which a provider is {@code DEGRADED}
 * @param downErrorRate error share from which it is {@code DOWN} — and therefore unselectable, which is
 *     what makes failover need no code of its own (FR-2.2)
 * @param downTimeoutRate share of unanswered attempts that takes it down on its own; a silent provider
 *     burns the OTP budget even while its error rate looks acceptable (TC-01)
 * @param recoveryAfter silence after which a {@code DOWN} provider is given traffic again; without it a
 *     provider that receives nothing can never produce the figures that would clear it
 */
@ConfigurationProperties("commhub.provider.health")
public record ProviderHealthProperties(
        Duration window,
        Integer minimumAttempts,
        Double degradedErrorRate,
        Double downErrorRate,
        Double downTimeoutRate,
        Duration recoveryAfter) {

    public ProviderHealthPolicy toPolicy() {
        ProviderHealthPolicy defaults = ProviderHealthPolicy.defaults();
        return new ProviderHealthPolicy(
                window == null ? defaults.window() : window,
                minimumAttempts == null ? defaults.minimumAttempts() : minimumAttempts,
                degradedErrorRate == null ? defaults.degradedErrorRate() : degradedErrorRate,
                downErrorRate == null ? defaults.downErrorRate() : downErrorRate,
                downTimeoutRate == null ? defaults.downTimeoutRate() : downTimeoutRate,
                recoveryAfter == null ? defaults.recoveryAfter() : recoveryAfter);
    }
}
