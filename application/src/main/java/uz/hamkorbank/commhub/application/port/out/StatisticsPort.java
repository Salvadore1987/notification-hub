package uz.hamkorbank.commhub.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;
import uz.hamkorbank.commhub.application.port.in.query.StatisticsQuery;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;

/**
 * Aggregated figures over the sends (§11.2 "Дашборд" and "Статистика", FR-6.2, OBS-01).
 *
 * <p>Counted in the database rather than read off Micrometer, and the difference is deliberate: metrics
 * are what an instance saw since it started and are scraped at an interval, while a report has to be the
 * same number twice and has to survive a rolling deploy. The alerting contour keeps its counters; the
 * admin panel counts rows.
 */
public interface StatisticsPort {

    /** One row per value of the query's dimension, in the natural order of that dimension. */
    List<StatisticsRow> aggregate(StatisticsQuery query);

    /**
     * p99 of accept → handed to a provider, in milliseconds, over the given class (TC-01).
     *
     * <p>The figure the OTP SLA is stated in, measured the same way {@code MetricsPort} measures it: on
     * the attempt the provider accepted, so retries and failovers are inside the number. Empty when the
     * class saw no traffic in the window — which is not the same as zero latency and must not be drawn
     * as such.
     */
    OptionalLong acceptToProviderP99Millis(Instant from, Instant to, TrafficClass trafficClass);
}
