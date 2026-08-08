package uz.hamkorbank.commhub.application.dto;

import java.util.Optional;
import uz.hamkorbank.commhub.domain.model.vo.Money;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * One row of a report (§11.2 "Статистика/Отчёты", FR-6.2).
 *
 * <p>{@code deliveryRate} is computed here rather than left to the caller, because a rate computed twice
 * is a rate that disagrees with itself: the screen, the CSV export and the dashboard all read this one.
 *
 * @param key the value of the dimension the report grouped by
 */
public record StatisticsRowView(
        String key,
        long accepted,
        long delivered,
        long failed,
        long rejected,
        long segments,
        Money cost,
        double deliveryRate) {

    public StatisticsRowView {
        Guard.notBlank(key, "StatisticsRowView.key");
    }

    public Optional<Money> costOptional() {
        return Optional.ofNullable(cost);
    }

    /** Messages neither delivered, failed nor rejected — still moving through the pipeline. */
    public long inFlight() {
        return Math.max(0, accepted - delivered - failed - rejected);
    }
}
