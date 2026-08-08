package uz.hamkorbank.commhub.adapter.out.persistence.delivery;

import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import uz.hamkorbank.commhub.adapter.out.persistence.support.SqlValues;
import uz.hamkorbank.commhub.application.port.out.ProviderDeliveryStats;
import uz.hamkorbank.commhub.application.port.out.ProviderStatsPort;
import uz.hamkorbank.commhub.domain.model.vo.ProviderId;

/**
 * {@link ProviderStatsPort} over {@code delivery_attempt} (FR-6.3, PR-02, OBS-01).
 *
 * <p>The health of a provider is read from the attempts the Hub already records — no counter to keep in
 * memory, no state to lose on a restart, and the same figures an operator sees in the message timeline.
 *
 * <p>Attempts still {@code PENDING} are excluded: an in-flight call is not yet evidence of anything, and
 * counting it as a failure would make every burst look like a degradation. {@code ERROR} and
 * {@code TIMEOUT} count as failures, {@code REJECTED} does not — a provider that answers "this number is
 * blacklisted" is working exactly as designed (§18.1, §18.2).
 *
 * <p>The window is scanned over the partitioned table by {@code request_at}, which is its partition key,
 * so a five-minute window touches one or two partitions (DB-02).
 */
@Repository
public class ProviderStatsPersistenceAdapter implements ProviderStatsPort {

    private static final String SELECT_STATS = """
            SELECT provider_id,
                   count(*)                                                      AS attempts,
                   count(*) FILTER (WHERE result IN ('ERROR', 'TIMEOUT'))        AS failures,
                   count(*) FILTER (WHERE result = 'TIMEOUT')                    AS timeouts,
                   COALESCE(avg(latency_ms) FILTER (WHERE latency_ms IS NOT NULL), 0) AS avg_latency
            FROM delivery_attempt
            WHERE request_at >= :from
              AND request_at < :to
              AND result <> 'PENDING'
            """;

    private final JdbcClient jdbcClient;

    public ProviderStatsPersistenceAdapter(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProviderDeliveryStats> statsSince(Instant from, Instant to) {
        return jdbcClient
                .sql(SELECT_STATS + " GROUP BY provider_id")
                .param("from", SqlValues.timestamp(from))
                .param("to", SqlValues.timestamp(to))
                .query((rs, rowNum) -> new ProviderDeliveryStats(
                        ProviderId.of(SqlValues.uuid(rs, "provider_id")),
                        rs.getLong("attempts"),
                        rs.getLong("failures"),
                        rs.getLong("timeouts"),
                        rs.getDouble("avg_latency")))
                .list();
    }

    @Override
    @Transactional(readOnly = true)
    public ProviderDeliveryStats statsOf(ProviderId providerId, Instant from, Instant to) {
        return jdbcClient
                .sql(SELECT_STATS + " AND provider_id = :providerId GROUP BY provider_id")
                .param("from", SqlValues.timestamp(from))
                .param("to", SqlValues.timestamp(to))
                .param("providerId", providerId.value())
                .query((rs, rowNum) -> new ProviderDeliveryStats(
                        providerId,
                        rs.getLong("attempts"),
                        rs.getLong("failures"),
                        rs.getLong("timeouts"),
                        rs.getDouble("avg_latency")))
                .optional()
                .orElseGet(() -> ProviderDeliveryStats.none(providerId));
    }
}
