package uz.hamkorbank.commhub.adapter.out.persistence.messaging;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import uz.hamkorbank.commhub.adapter.out.persistence.json.TimingJson;
import uz.hamkorbank.commhub.adapter.out.persistence.support.JsonCodec;
import uz.hamkorbank.commhub.adapter.out.persistence.support.SqlValues;
import uz.hamkorbank.commhub.application.port.in.query.BatchListQuery;
import uz.hamkorbank.commhub.application.port.out.BatchRepository;
import uz.hamkorbank.commhub.domain.model.Batch;
import uz.hamkorbank.commhub.domain.model.type.BatchStatus;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.vo.BatchId;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;

/** {@link BatchRepository} over the {@code batch} table (§10.1, FR-1.6, FR-3.1). */
@Repository
public class BatchPersistenceAdapter implements BatchRepository {

    private static final String SELECT = """
            SELECT id, stream_id, channel, status, total, processed, sent, delivered, failed,
                   cost_estimate, cost_currency, timing, created_at
            FROM batch
            """;

    /**
     * Filters of the batch list, written once so the page and its count cannot disagree (UI-03).
     *
     * <p>"Active" is spelled out here as "not terminal" rather than as a list of statuses, so a status
     * added to {@code BatchStatus} later does not silently drop out of the dashboard.
     */
    private static final String WHERE = """
             WHERE created_at >= :from
               AND created_at < :to
               AND (CAST(:streamId AS varchar) IS NULL OR stream_id = :streamId)
               AND (CAST(:channel AS varchar) IS NULL OR channel = :channel)
               AND (CAST(:status AS varchar) IS NULL OR status = :status)
               AND (NOT CAST(:activeOnly AS boolean) OR status NOT IN ('STOPPED', 'COMPLETED'))
            """;

    private static final String UPSERT = """
            INSERT INTO batch (id, stream_id, channel, status, total, processed, sent, delivered, failed,
                               cost_estimate, cost_currency, timing, created_at)
            VALUES (:id, :streamId, :channel, :status, :total, :processed, :sent, :delivered, :failed,
                    :costEstimate, :costCurrency, CAST(:timing AS jsonb), :createdAt)
            ON CONFLICT (id) DO UPDATE SET
                status = EXCLUDED.status,
                total = EXCLUDED.total,
                processed = EXCLUDED.processed,
                sent = EXCLUDED.sent,
                delivered = EXCLUDED.delivered,
                failed = EXCLUDED.failed,
                cost_estimate = EXCLUDED.cost_estimate,
                cost_currency = EXCLUDED.cost_currency,
                timing = EXCLUDED.timing,
                updated_at = now()
            """;

    private final JdbcClient jdbcClient;
    private final JsonCodec jsonCodec;

    public BatchPersistenceAdapter(JdbcClient jdbcClient, JsonCodec jsonCodec) {
        this.jdbcClient = jdbcClient;
        this.jsonCodec = jsonCodec;
    }

    @Override
    @Transactional
    public Batch save(Batch batch) {
        jdbcClient
                .sql(UPSERT)
                .param("id", batch.id().value())
                .param("streamId", batch.streamId().value())
                .param("channel", batch.channel().name())
                .param("status", batch.status().name())
                .param("total", batch.progress().total())
                .param("processed", batch.progress().processed())
                .param("sent", batch.progress().sent())
                .param("delivered", batch.progress().delivered())
                .param("failed", batch.progress().failed())
                .param("costEstimate", SqlValues.amountOf(batch.costEstimate().orElse(null)))
                .param("costCurrency", SqlValues.currencyOf(batch.costEstimate().orElse(null)))
                .param("timing", jsonCodec.write(TimingJson.of(batch.timing())))
                .param("createdAt", SqlValues.timestamp(batch.createdAt()))
                .update();
        return batch;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Batch> findById(BatchId batchId) {
        return jdbcClient
                .sql(SELECT + " WHERE id = :id")
                .param("id", batchId.value())
                .query(rowMapper())
                .optional();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Batch> findByStream(StreamId streamId, BatchStatus status, int limit) {
        return jdbcClient
                .sql(SELECT + " WHERE stream_id = :streamId AND (:status IS NULL OR status = :status)"
                        + " ORDER BY created_at DESC LIMIT :limit")
                .param("streamId", streamId.value())
                .param("status", SqlValues.nameOf(status))
                .param("limit", limit)
                .query(rowMapper())
                .list();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Batch> search(BatchListQuery query) {
        return bind(jdbcClient.sql(SELECT + WHERE + " ORDER BY created_at DESC LIMIT :limit OFFSET :offset"), query)
                .param("limit", query.limit())
                .param("offset", query.offset())
                .query(rowMapper())
                .list();
    }

    @Override
    @Transactional(readOnly = true)
    public long count(BatchListQuery query) {
        return bind(jdbcClient.sql("SELECT count(*) FROM batch" + WHERE), query)
                .query(Long.class)
                .single();
    }

    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec statement, BatchListQuery query) {
        return statement
                .param("from", SqlValues.timestamp(query.from()))
                .param("to", SqlValues.timestamp(query.to()))
                .param(
                        "streamId",
                        query.streamId() == null ? null : query.streamId().value())
                .param("channel", SqlValues.nameOf(query.channel()))
                .param("status", SqlValues.nameOf(query.status()))
                .param("activeOnly", query.activeOnly());
    }

    private RowMapper<Batch> rowMapper() {
        return (rs, rowNum) -> Batch.rehydrate(
                        BatchId.of(SqlValues.uuid(rs, "id")),
                        StreamId.of(rs.getString("stream_id")),
                        SqlValues.enumValue(rs, "channel", Channel.class),
                        TimingJson.toDomain(jsonCodec.read(rs.getString("timing"), TimingJson.class)),
                        SqlValues.instant(rs, "created_at"))
                .status(SqlValues.enumValue(rs, "status", BatchStatus.class))
                .progress(rs.getLong("total"), rs.getLong("processed"), rs.getLong("sent"), rs.getLong("delivered"))
                .failed(rs.getLong("failed"))
                .costEstimate(SqlValues.money(rs, "cost_estimate", "cost_currency"))
                .build();
    }
}
