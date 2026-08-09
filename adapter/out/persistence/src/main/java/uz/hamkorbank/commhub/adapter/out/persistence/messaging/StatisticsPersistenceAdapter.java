package uz.hamkorbank.commhub.adapter.out.persistence.messaging;

import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import uz.hamkorbank.commhub.adapter.out.persistence.support.AnalyticsJdbcClient;
import uz.hamkorbank.commhub.adapter.out.persistence.support.SqlValues;
import uz.hamkorbank.commhub.application.port.in.query.StatisticsDimension;
import uz.hamkorbank.commhub.application.port.in.query.StatisticsQuery;
import uz.hamkorbank.commhub.application.port.out.StatisticsPort;
import uz.hamkorbank.commhub.application.port.out.StatisticsRow;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * {@link StatisticsPort} over {@code message} and {@code delivery_attempt} (§11.2, FR-6.2, TC-01).
 *
 * <p>Counted in the database. A report over a month of a partitioned table is an aggregate the planner
 * can prune and parallelise; the same figures streamed through the application would be a million rows
 * crossing the wire so that a loop could add them up.
 *
 * <p>The three outcome buckets are the canonical statuses of §6.3 grouped the way the screen reads them,
 * and the grouping is spelled out here rather than derived: {@code REJECTED} and {@code DUPLICATE} never
 * reached a provider and belong to the pipeline (IR-01), {@code UNDELIVERED}, {@code EXPIRED},
 * {@code FAILED} and {@code CANCELLED} did or would have and belong to delivery. Everything else is
 * still moving, which is why the buckets deliberately do not add up to {@code accepted}.
 *
 * <p>Cost is summed over the rows of the group and reported in the currency those rows carry. The Bank
 * tariffs in one currency, so this is a sum and not a currency conversion; if a second one ever appears
 * the report has to be read per provider, which is where a tariff actually lives — a total mixing two
 * currencies would be a number with no meaning, and this is the place it would be produced.
 */
@Repository
public class StatisticsPersistenceAdapter implements StatisticsPort {

    private static final String DELIVERED = "'DELIVERED'";
    private static final String FAILED = "'UNDELIVERED', 'EXPIRED', 'FAILED', 'CANCELLED'";
    private static final String REJECTED = "'REJECTED', 'DUPLICATE'";

    private static final String AGGREGATE = """
            SELECT %s AS dimension_key,
                   count(*)                                                   AS accepted,
                   count(*) FILTER (WHERE status IN (%s))                     AS delivered,
                   count(*) FILTER (WHERE status IN (%s))                     AS failed,
                   count(*) FILTER (WHERE status IN (%s))                     AS rejected,
                   COALESCE(sum(segments), 0)                                 AS segments,
                   sum(cost)                                                  AS cost,
                   max(cost_currency)                                         AS cost_currency
            FROM message
            WHERE accepted_at >= :from
              AND accepted_at < :to
              AND (CAST(:includeTest AS boolean) OR NOT test)
              AND (CAST(:channel AS varchar) IS NULL OR selected_channel = :channel
                   OR (selected_channel IS NULL AND channel_plan -> 'channels' ->> 0 = :channel))
              AND (CAST(:streamId AS varchar) IS NULL OR stream_id = :streamId)
              AND (CAST(:provider AS varchar) IS NULL OR selected_provider_code = :provider)
              AND (CAST(:batchId AS uuid) IS NULL OR batch_id = :batchId)
            GROUP BY 1
            HAVING %s IS NOT NULL
            ORDER BY 1
            """;

    /**
     * p99 of accept → handed to a provider (TC-01).
     *
     * <p>Measured on the attempt the provider <em>accepted</em>, which is what makes retries and
     * failovers part of the figure rather than invisible next to it — the same rule
     * {@code MetricsPort.acceptToProvider} follows, so the dashboard and the alert cannot tell two
     * stories about the same SLA.
     */
    private static final String LATENCY_P99 = """
            SELECT percentile_disc(0.99) WITHIN GROUP (
                       ORDER BY EXTRACT(EPOCH FROM (a.request_at - m.accepted_at)) * 1000)
            FROM delivery_attempt a
            JOIN message m ON m.id = a.message_id AND m.accepted_at >= :from AND m.accepted_at < :to
            WHERE a.request_at >= :from
              AND a.request_at < :to
              AND a.result = 'ACCEPTED'
              AND m.traffic_class = :trafficClass
              AND NOT m.test
            """;

    private final AnalyticsJdbcClient jdbcClient;

    public StatisticsPersistenceAdapter(AnalyticsJdbcClient jdbcClient) {
        this.jdbcClient = Guard.notNull(jdbcClient, "jdbcClient");
    }

    @Override
    @Transactional(readOnly = true)
    public List<StatisticsRow> aggregate(StatisticsQuery query) {
        Guard.notNull(query, "query");
        String keyExpression = keyExpressionOf(query.dimension());
        return jdbcClient
                .client()
                .sql(AGGREGATE.formatted(keyExpression, DELIVERED, FAILED, REJECTED, keyExpression))
                .param("from", SqlValues.timestamp(query.from()))
                .param("to", SqlValues.timestamp(query.to()))
                .param("includeTest", query.includeTest())
                .param("channel", SqlValues.nameOf(query.channel()))
                .param(
                        "streamId",
                        query.streamId() == null ? null : query.streamId().value())
                .param(
                        "provider",
                        query.provider() == null ? null : query.provider().value())
                .param(
                        "batchId",
                        query.batchId() == null ? null : query.batchId().value())
                .query((rs, rowNum) -> new StatisticsRow(
                        rs.getString("dimension_key"),
                        rs.getLong("accepted"),
                        rs.getLong("delivered"),
                        rs.getLong("failed"),
                        rs.getLong("rejected"),
                        rs.getLong("segments"),
                        SqlValues.money(rs, "cost", "cost_currency")))
                .list();
    }

    @Override
    @Transactional(readOnly = true)
    public OptionalLong acceptToProviderP99Millis(Instant from, Instant to, TrafficClass trafficClass) {
        Guard.notNull(trafficClass, "trafficClass");
        Double millis = jdbcClient
                .client()
                .sql(LATENCY_P99)
                .param("from", SqlValues.timestamp(from))
                .param("to", SqlValues.timestamp(to))
                .param("trafficClass", trafficClass.name())
                .query(Double.class)
                .optional()
                .orElse(null);
        return millis == null ? OptionalLong.empty() : OptionalLong.of(Math.round(millis));
    }

    /**
     * The column a dimension groups by, chosen from a closed set.
     *
     * <p>An enum decides it and the caller never names a column: a report that lets the request pick the
     * grouping expression is a report that lets the request pick any expression. The channel falls back
     * to the requested one for messages that never got routed, so a rejected submission still lands in
     * the channel it asked for instead of an empty bucket.
     */
    private static String keyExpressionOf(StatisticsDimension dimension) {
        return switch (dimension) {
            case CHANNEL -> "COALESCE(selected_channel, channel_plan -> 'channels' ->> 0)";
            case PROVIDER -> "selected_provider_code";
            case STREAM -> "stream_id";
            case BATCH -> "CAST(batch_id AS text)";
            case DAY -> "to_char(accepted_at AT TIME ZONE 'UTC', 'YYYY-MM-DD')";
            case HOUR -> "to_char(accepted_at AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:00')";
        };
    }
}
