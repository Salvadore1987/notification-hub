package uz.hamkorbank.commhub.application.port.in.query;

import java.time.Instant;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * The summary screen of the admin panel over one period (§11.2 "Дашборд", UI-03).
 *
 * <p>The dashboard is polled — by every open browser tab, every few seconds — so it is deliberately one
 * query with one period rather than a screen assembled from a dozen calls. What it costs is what its
 * period costs, which is why the period is the caller's and not a constant.
 */
public record DashboardQuery(Instant from, Instant to, boolean includeTest) {

    public DashboardQuery {
        Guard.notNull(from, "DashboardQuery.from");
        Guard.notNull(to, "DashboardQuery.to");
        Guard.isTrue(!to.isBefore(from), "DashboardQuery.to precedes DashboardQuery.from");
    }

    /** The period, without the test sends of FR-7.4. */
    public static DashboardQuery of(Instant from, Instant to) {
        return new DashboardQuery(from, to, false);
    }
}
