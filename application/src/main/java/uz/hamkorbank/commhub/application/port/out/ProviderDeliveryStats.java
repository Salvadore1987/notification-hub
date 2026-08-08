package uz.hamkorbank.commhub.application.port.out;

import uz.hamkorbank.commhub.domain.model.vo.ProviderId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Attempts of one provider inside a window (FR-6.3, PR-02).
 *
 * @param attempts delivery attempts made against the provider
 * @param failures attempts the provider refused or did not answer at all
 * @param timeouts subset of {@code failures} where no answer arrived; a silent provider is worse than
 *     a refusing one, so the monitor weighs it separately
 * @param averageLatencyMillis mean round trip of the answered attempts; 0 when there were none
 */
public record ProviderDeliveryStats(
        ProviderId providerId, long attempts, long failures, long timeouts, double averageLatencyMillis) {

    public ProviderDeliveryStats {
        Guard.notNull(providerId, "ProviderDeliveryStats.providerId");
        Guard.notNegative(attempts, "ProviderDeliveryStats.attempts");
        Guard.notNegative(failures, "ProviderDeliveryStats.failures");
        Guard.notNegative(timeouts, "ProviderDeliveryStats.timeouts");
        Guard.isTrue(failures <= attempts, "ProviderDeliveryStats.failures must not exceed attempts");
        Guard.isTrue(timeouts <= failures, "ProviderDeliveryStats.timeouts must not exceed failures");
    }

    public static ProviderDeliveryStats none(ProviderId providerId) {
        return new ProviderDeliveryStats(providerId, 0L, 0L, 0L, 0.0d);
    }

    /** Whether the window holds enough attempts for a rate to mean anything. */
    public boolean hasAtLeast(int minimumAttempts) {
        return attempts >= minimumAttempts;
    }

    /** Share of attempts that failed, between 0 and 1; 0 for an idle provider. */
    public double errorRate() {
        return attempts == 0L ? 0.0d : (double) failures / attempts;
    }

    /** Share of attempts the provider left unanswered, between 0 and 1. */
    public double timeoutRate() {
        return attempts == 0L ? 0.0d : (double) timeouts / attempts;
    }

    public boolean isIdle() {
        return attempts == 0L;
    }
}
