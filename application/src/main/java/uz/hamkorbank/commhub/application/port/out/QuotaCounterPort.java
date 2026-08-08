package uz.hamkorbank.commhub.application.port.out;

import java.time.Instant;
import uz.hamkorbank.commhub.domain.model.QuotaConfig;
import uz.hamkorbank.commhub.domain.model.vo.Money;

/**
 * Accumulated count and cost per quota window (§10.1 {@code quota_counter}, FR-2.6).
 *
 * <p>The verdict itself is domain logic — {@link QuotaConfig#evaluate} — this port only supplies and
 * advances the counters.
 */
public interface QuotaCounterPort {

    /** Usage accumulated in the window that contains {@code now}. */
    QuotaConfig.Usage usage(QuotaScope scope, QuotaWindow window, Instant now);

    /** Registers a send against the day and month counters of the scope. */
    void register(QuotaScope scope, long count, Money cost, Instant occurredAt);
}
