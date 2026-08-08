package uz.hamkorbank.commhub.application.policy;

import java.time.Duration;
import uz.hamkorbank.commhub.application.port.out.ProviderDeliveryStats;
import uz.hamkorbank.commhub.domain.model.type.ProviderHealthStatus;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Thresholds that turn delivery figures into a provider health status (FR-6.3, PR-02).
 *
 * <p>Health is what makes failover automatic: a provider marked {@code DOWN} stops being selectable
 * ({@code Provider.isSelectable}), so the router picks the reserve on the very next message without
 * anyone touching the configuration (FR-2.2).
 *
 * <p><b>Failback</b> is the harder half, and it is why the policy needs {@code recoveryAfter}: a
 * {@code DOWN} provider receives no traffic, so no new figures can ever prove it recovered. After that
 * much silence its status returns to {@code UNKNOWN} — selectable again, but not yet trusted — and the
 * first window with traffic and no failures promotes it back to {@code UP}. That is a circuit breaker's
 * half-open state, one level up: the breaker of {@code ProviderCallExecutor} protects a single call,
 * this protects the routing decision.
 *
 * @param window how far back the figures are read
 * @param minimumAttempts attempts below which a rate is noise and the status is left alone
 * @param degradedErrorRate error share from which the provider is {@code DEGRADED}
 * @param downErrorRate error share from which it is {@code DOWN}
 * @param downTimeoutRate share of unanswered attempts from which it is {@code DOWN}, even when the
 *     total error rate is lower — a silent provider burns the OTP budget (TC-01)
 * @param recoveryAfter silence after which a {@code DOWN} provider is given traffic again
 */
public record ProviderHealthPolicy(
        Duration window,
        int minimumAttempts,
        double degradedErrorRate,
        double downErrorRate,
        double downTimeoutRate,
        Duration recoveryAfter) {

    public ProviderHealthPolicy {
        Guard.notNull(window, "ProviderHealthPolicy.window");
        Guard.positive(minimumAttempts, "ProviderHealthPolicy.minimumAttempts");
        Guard.notNull(recoveryAfter, "ProviderHealthPolicy.recoveryAfter");
        Guard.isTrue(
                degradedErrorRate > 0 && degradedErrorRate < 1,
                "ProviderHealthPolicy.degradedErrorRate must be a rate");
        Guard.isTrue(downErrorRate > 0 && downErrorRate <= 1, "ProviderHealthPolicy.downErrorRate must be a rate");
        Guard.isTrue(
                downTimeoutRate > 0 && downTimeoutRate <= 1, "ProviderHealthPolicy.downTimeoutRate must be a rate");
        Guard.isTrue(
                downErrorRate > degradedErrorRate,
                "ProviderHealthPolicy.downErrorRate must be above degradedErrorRate");
    }

    /** Five-minute window, 20 % errors degrade, 50 % errors (or 30 % silence) take a provider down. */
    public static ProviderHealthPolicy defaults() {
        return new ProviderHealthPolicy(Duration.ofMinutes(5), 20, 0.2d, 0.5d, 0.3d, Duration.ofMinutes(2));
    }

    /**
     * Health a provider should carry given its recent figures.
     *
     * @param current status it carries now
     * @param sinceStatusChange how long the provider has carried {@code current}
     */
    public ProviderHealthStatus evaluate(
            ProviderDeliveryStats stats, ProviderHealthStatus current, Duration sinceStatusChange) {
        Guard.notNull(stats, "stats");
        Guard.notNull(current, "current");
        Guard.notNull(sinceStatusChange, "sinceStatusChange");
        if (stats.isIdle()) {
            return isProbationDue(current, sinceStatusChange) ? ProviderHealthStatus.UNKNOWN : current;
        }
        if (stats.failures() == 0L) {
            return ProviderHealthStatus.UP;
        }
        if (!stats.hasAtLeast(minimumAttempts)) {
            return current;
        }
        if (stats.errorRate() >= downErrorRate || stats.timeoutRate() >= downTimeoutRate) {
            return ProviderHealthStatus.DOWN;
        }
        return stats.errorRate() >= degradedErrorRate ? ProviderHealthStatus.DEGRADED : ProviderHealthStatus.UP;
    }

    /** Whether a {@code DOWN} provider has been quiet long enough to be tried again (FR-6.3). */
    public boolean isProbationDue(ProviderHealthStatus current, Duration sinceStatusChange) {
        return current == ProviderHealthStatus.DOWN && sinceStatusChange.compareTo(recoveryAfter) >= 0;
    }
}
