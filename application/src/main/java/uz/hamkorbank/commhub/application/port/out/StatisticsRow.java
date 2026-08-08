package uz.hamkorbank.commhub.application.port.out;

import uz.hamkorbank.commhub.domain.model.vo.Money;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * One row of a report (§11.2 "Статистика/Отчёты", FR-6.2).
 *
 * <p>{@code key} is whatever the dimension grouped by — a channel name, a provider code, a stream id,
 * an ISO date — because a report that returns a differently shaped row per dimension is a report every
 * consumer has to switch over.
 *
 * <p>The counts are of messages, not of transitions: {@code delivered} counts the messages that reached
 * {@code DELIVERED}, {@code failed} the ones that ended undelivered, expired or failed, and
 * {@code rejected} the ones the pipeline refused before anything was sent (IR-01). Their sum is below
 * {@code accepted} by exactly the messages still in flight, which is the number an operator reads the
 * report to find.
 *
 * @param cost {@code null} when nothing in the row had a tariff yet
 */
public record StatisticsRow(
        String key, long accepted, long delivered, long failed, long rejected, long segments, Money cost) {

    public StatisticsRow {
        Guard.notBlank(key, "StatisticsRow.key");
        Guard.notNegative(accepted, "StatisticsRow.accepted");
        Guard.notNegative(delivered, "StatisticsRow.delivered");
        Guard.notNegative(failed, "StatisticsRow.failed");
        Guard.notNegative(rejected, "StatisticsRow.rejected");
        Guard.notNegative(segments, "StatisticsRow.segments");
    }

    /** Share of accepted messages that were confirmed delivered; 0 while nothing was accepted. */
    public double deliveryRate() {
        return accepted == 0 ? 0 : (double) delivered / accepted;
    }
}
