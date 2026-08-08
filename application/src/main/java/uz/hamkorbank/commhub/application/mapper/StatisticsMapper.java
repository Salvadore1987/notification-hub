package uz.hamkorbank.commhub.application.mapper;

import java.util.List;
import org.mapstruct.Mapper;
import uz.hamkorbank.commhub.application.dto.DashboardView;
import uz.hamkorbank.commhub.application.dto.StatisticsRowView;
import uz.hamkorbank.commhub.application.port.out.StatisticsRow;
import uz.hamkorbank.commhub.domain.model.Provider;
import uz.hamkorbank.commhub.domain.model.vo.Money;

/**
 * Aggregated rows → the report and dashboard read models (FR-6.2, §11.2).
 *
 * <p>The totals are summed here rather than asked of the database a second time: a second query would be
 * a second period, a second set of filters and a second chance for the sum under the table to disagree
 * with the rows in it.
 */
@Mapper(componentModel = "spring")
public interface StatisticsMapper {

    default StatisticsRowView toView(StatisticsRow row) {
        return new StatisticsRowView(
                row.key(),
                row.accepted(),
                row.delivered(),
                row.failed(),
                row.rejected(),
                row.segments(),
                row.cost(),
                row.deliveryRate());
    }

    default List<StatisticsRowView> toViews(List<StatisticsRow> rows) {
        return rows.stream().map(this::toView).toList();
    }

    /** Rolls a report up into the headline figures of the dashboard. */
    default DashboardView.Totals toTotals(List<StatisticsRowView> rows) {
        long accepted = rows.stream().mapToLong(StatisticsRowView::accepted).sum();
        long delivered = rows.stream().mapToLong(StatisticsRowView::delivered).sum();
        long failed = rows.stream().mapToLong(StatisticsRowView::failed).sum();
        long rejected = rows.stream().mapToLong(StatisticsRowView::rejected).sum();
        long segments = rows.stream().mapToLong(StatisticsRowView::segments).sum();
        return new DashboardView.Totals(
                accepted,
                delivered,
                failed,
                rejected,
                Math.max(0, accepted - delivered - failed - rejected),
                segments,
                totalCost(rows),
                accepted == 0 ? 0 : (double) delivered / accepted);
    }

    /**
     * Health line of one provider for the dashboard (FR-6.3).
     *
     * <p>Built from the aggregate rather than from {@link uz.hamkorbank.commhub.application.dto.ProviderView},
     * which would drag the endpoint configuration of every provider into a screen that is polled every
     * few seconds and shows none of it.
     */
    default DashboardView.ProviderHealthLine toHealthLine(Provider provider) {
        return new DashboardView.ProviderHealthLine(
                provider.code(), provider.channel(), provider.health(), provider.isSelectable());
    }

    /**
     * Sum of the row costs, or {@code null} when no row had one.
     *
     * <p>Rows of different currencies are not summed — {@link Money#plus} refuses that, and rightly:
     * a total mixing two currencies is a number with no meaning. In a deployment where a channel is
     * tariffed in a second currency the report is read per provider, which is where the tariff lives.
     */
    private static Money totalCost(List<StatisticsRowView> rows) {
        return rows.stream()
                .map(StatisticsRowView::cost)
                .filter(cost -> cost != null)
                .reduce(Money::plus)
                .orElse(null);
    }
}
