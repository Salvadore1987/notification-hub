package uz.hamkorbank.commhub.domain.model;

import java.util.Optional;
import uz.hamkorbank.commhub.domain.model.type.QuotaExhaustionBehavior;
import uz.hamkorbank.commhub.domain.model.type.QuotaVerdict;
import uz.hamkorbank.commhub.domain.model.vo.Money;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Count and cost quotas of a stream, channel or provider (FR-2.6).
 *
 * <p>A {@code null} limit means "unlimited". When a limit is breached the configured
 * {@link QuotaExhaustionBehavior} decides between blocking with an alert and alerting only.
 */
public record QuotaConfig(
        Long dailyCount, Long monthlyCount, Money dailyCost, Money monthlyCost, QuotaExhaustionBehavior behavior) {

    public QuotaConfig {
        Guard.notNull(behavior, "QuotaConfig.behavior");
        if (dailyCount != null) {
            Guard.notNegative(dailyCount, "QuotaConfig.dailyCount");
        }
        if (monthlyCount != null) {
            Guard.notNegative(monthlyCount, "QuotaConfig.monthlyCount");
        }
    }

    public static QuotaConfig unlimited() {
        return new QuotaConfig(null, null, null, null, QuotaExhaustionBehavior.ALERT_ONLY);
    }

    public static QuotaConfig ofCounts(Long dailyCount, Long monthlyCount, QuotaExhaustionBehavior behavior) {
        return new QuotaConfig(dailyCount, monthlyCount, null, null, behavior);
    }

    /**
     * Decides whether one more send fits into the quotas.
     *
     * @param daily usage accumulated within the current day
     * @param monthly usage accumulated within the current month
     * @param additionalCount number of messages (or segments) about to be sent
     * @param additionalCost expected cost of that send; {@code null} when unknown
     */
    public QuotaVerdict evaluate(Usage daily, Usage monthly, long additionalCount, Money additionalCost) {
        Guard.notNull(daily, "daily");
        Guard.notNull(monthly, "monthly");
        Guard.notNegative(additionalCount, "additionalCount");
        boolean exceeded = exceedsCount(dailyCount, daily.count(), additionalCount)
                || exceedsCount(monthlyCount, monthly.count(), additionalCount)
                || exceedsCost(dailyCost, daily.cost(), additionalCost)
                || exceedsCost(monthlyCost, monthly.cost(), additionalCost);
        if (!exceeded) {
            return QuotaVerdict.ALLOWED;
        }
        return behavior == QuotaExhaustionBehavior.BLOCK_AND_ALERT ? QuotaVerdict.BLOCKED : QuotaVerdict.ALERT;
    }

    public boolean isUnlimited() {
        return dailyCount == null && monthlyCount == null && dailyCost == null && monthlyCost == null;
    }

    public Optional<Long> dailyCountLimit() {
        return Optional.ofNullable(dailyCount);
    }

    public Optional<Long> monthlyCountLimit() {
        return Optional.ofNullable(monthlyCount);
    }

    private static boolean exceedsCount(Long limit, long used, long additional) {
        return limit != null && used + additional > limit;
    }

    private static boolean exceedsCost(Money limit, Money used, Money additional) {
        if (limit == null) {
            return false;
        }
        Money projected = used == null ? Money.zero(limit.currency()) : used;
        if (additional != null) {
            projected = projected.plus(additional);
        }
        return projected.isGreaterThan(limit);
    }

    /**
     * Accumulated usage of a quota window (§10.1 {@code quota_counter}).
     *
     * @param cost accumulated cost; {@code null} when cost is not tracked
     */
    public record Usage(long count, Money cost) {

        public Usage {
            Guard.notNegative(count, "Usage.count");
        }

        public static Usage none() {
            return new Usage(0L, null);
        }

        public static Usage of(long count) {
            return new Usage(count, null);
        }
    }
}
