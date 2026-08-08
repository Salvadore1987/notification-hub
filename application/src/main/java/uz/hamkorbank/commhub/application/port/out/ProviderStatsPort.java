package uz.hamkorbank.commhub.application.port.out;

import java.time.Instant;
import java.util.List;
import uz.hamkorbank.commhub.domain.model.vo.ProviderId;

/**
 * Delivery figures of the providers over a sliding window (FR-6.3, PR-02, OBS-01).
 *
 * <p>The passive half of the health detection: the Hub already writes an outcome and a latency for
 * every delivery attempt, so degradation can be read off real traffic without a synthetic probe —
 * which for SMS would mean sending a chargeable message to a real number.
 */
public interface ProviderStatsPort {

    /** Attempts of every provider that saw traffic between {@code from} and {@code to} (FR-6.3). */
    List<ProviderDeliveryStats> statsSince(Instant from, Instant to);

    /** Figures of one provider in the same window; empty counters when it saw no traffic. */
    ProviderDeliveryStats statsOf(ProviderId providerId, Instant from, Instant to);
}
