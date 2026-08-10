package uz.hamkorbank.commhub.adapter.out.persistence.messaging;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import uz.hamkorbank.commhub.adapter.out.persistence.crypto.ContentCodec;
import uz.hamkorbank.commhub.adapter.out.persistence.json.TimingJson;
import uz.hamkorbank.commhub.adapter.out.persistence.support.JsonCodec;
import uz.hamkorbank.commhub.adapter.out.persistence.support.SqlValues;
import uz.hamkorbank.commhub.application.port.in.query.BatchListQuery;
import uz.hamkorbank.commhub.application.port.out.BatchRepository;
import uz.hamkorbank.commhub.domain.model.Batch;
import uz.hamkorbank.commhub.domain.model.TemplateRef;
import uz.hamkorbank.commhub.domain.model.type.BatchStatus;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.ContentLocale;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;
import uz.hamkorbank.commhub.domain.model.vo.BatchId;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;
import uz.hamkorbank.commhub.domain.model.vo.TemplateCode;

/** {@link BatchRepository} over the {@code batch} table (§10.1, FR-1.6, FR-3.1). */
@Repository
public class BatchPersistenceAdapter implements BatchRepository {

    private static final String SELECT = """
            SELECT id, stream_id, channel, status, total, processed, sent, delivered, failed,
                   cost_estimate, cost_currency, timing, traffic_class, test,
                   template_code, template_locale, template_variables, created_at
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

    /**
     * Counter change applied in one statement, never as a read-modify-write (FR-3.1, ADR-0040).
     *
     * <p>{@link #save(Batch)} writes the counters as absolute values taken from an aggregate loaded a
     * moment earlier, so two dispatch threads on the same batch would lose one of their increments.
     * A progress counter is a concurrent accumulator, like the quota and frequency counters, and is
     * treated as one.
     *
     * <p>{@code GREATEST(0, …)} mirrors {@code Batch.apply}: a DLQ retry sends negative components, and
     * the {@code CHECK} on the table would otherwise refuse the write.
     */
    private static final String APPLY_PROGRESS = """
            UPDATE batch
               SET processed = GREATEST(0, processed + :processed),
                   sent      = GREATEST(0, sent + :sent),
                   delivered = GREATEST(0, delivered + :delivered),
                   failed    = GREATEST(0, failed + :failed),
                   updated_at = now()
             WHERE id = :id
            RETURNING total, processed, sent, delivered, failed
            """;

    private static final String UPSERT = """
            INSERT INTO batch (id, stream_id, channel, status, total, processed, sent, delivered, failed,
                               cost_estimate, cost_currency, timing, traffic_class, test,
                               template_code, template_locale, template_variables, created_at)
            VALUES (:id, :streamId, :channel, :status, :total, :processed, :sent, :delivered, :failed,
                    :costEstimate, :costCurrency, CAST(:timing AS jsonb), :trafficClass, :test,
                    :templateCode, :templateLocale, CAST(:templateVariables AS jsonb), :createdAt)
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
                traffic_class = EXCLUDED.traffic_class,
                test = EXCLUDED.test,
                template_code = EXCLUDED.template_code,
                template_locale = EXCLUDED.template_locale,
                template_variables = EXCLUDED.template_variables,
                updated_at = now()
            """;

    private final JdbcClient jdbcClient;
    private final JsonCodec jsonCodec;
    private final ContentCodec contentCodec;

    public BatchPersistenceAdapter(JdbcClient jdbcClient, JsonCodec jsonCodec, ContentCodec contentCodec) {
        this.jdbcClient = jdbcClient;
        this.jsonCodec = jsonCodec;
        this.contentCodec = contentCodec;
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
                .param("trafficClass", SqlValues.nameOf(batch.itemDefaults().trafficClass()))
                .param("test", batch.itemDefaults().test())
                .param(
                        "templateCode",
                        batch.itemDefaults()
                                .templateOptional()
                                .map(template -> template.code().value())
                                .orElse(null))
                .param(
                        "templateLocale",
                        batch.itemDefaults()
                                .templateOptional()
                                .map(template -> SqlValues.nameOf(template.locale()))
                                .orElse(null))
                .param(
                        "templateVariables",
                        batch.itemDefaults()
                                .templateOptional()
                                .map(template -> contentCodec.write(template.variables()))
                                .orElse(null))
                .param("createdAt", SqlValues.timestamp(batch.createdAt()))
                .update();
        return batch;
    }

    @Override
    @Transactional
    public Batch.Progress applyProgress(BatchId batchId, Batch.Delta delta) {
        return jdbcClient
                .sql(APPLY_PROGRESS)
                .param("id", batchId.value())
                .param("processed", delta.processed())
                .param("sent", delta.sent())
                .param("delivered", delta.delivered())
                .param("failed", delta.failed())
                .query((rs, rowNum) -> new Batch.Progress(
                        rs.getLong("total"),
                        rs.getLong("processed"),
                        rs.getLong("sent"),
                        rs.getLong("delivered"),
                        rs.getLong("failed")))
                .optional()
                .orElseGet(() -> new Batch.Progress(0, 0, 0, 0, 0));
    }

    @Override
    @Transactional
    public void markCompleted(BatchId batchId) {
        jdbcClient
                .sql("UPDATE batch SET status = 'COMPLETED', updated_at = now()"
                        + " WHERE id = :id AND status = 'PROCESSING'")
                .param("id", batchId.value())
                .update();
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
                .itemDefaults(itemDefaults(rs))
                .build();
    }

    /** The header the items of this batch inherit, read back from its columns (FR-1.6). */
    private Batch.ItemDefaults itemDefaults(java.sql.ResultSet rs) throws java.sql.SQLException {
        String templateCode = rs.getString("template_code");
        TemplateRef template = templateCode == null
                ? null
                : new TemplateRef(
                        TemplateCode.of(templateCode),
                        SqlValues.enumValue(rs, "template_locale", ContentLocale.class),
                        contentCodec.readStringMap(rs.getString("template_variables")));
        return new Batch.ItemDefaults(
                SqlValues.enumValue(rs, "traffic_class", TrafficClass.class), template, rs.getBoolean("test"));
    }
}
