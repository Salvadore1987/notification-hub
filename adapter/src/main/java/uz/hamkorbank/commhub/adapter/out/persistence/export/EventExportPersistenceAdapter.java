package uz.hamkorbank.commhub.adapter.out.persistence.export;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import uz.hamkorbank.commhub.adapter.out.persistence.support.SqlValues;
import uz.hamkorbank.commhub.application.port.out.DeliveryEvent;
import uz.hamkorbank.commhub.application.port.out.DeliveryEvent.DeliveryOutcome;
import uz.hamkorbank.commhub.application.port.out.EventExportRepository;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.MessageStatus;
import uz.hamkorbank.commhub.domain.model.type.RejectionReason;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;
import uz.hamkorbank.commhub.domain.model.vo.BatchId;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Reads finished sends for the data-mart feed (FR-6.4).
 *
 * <p>Deliberately not built on {@code MessageRepository}. That port rebuilds whole aggregates, with their
 * contents decrypted (DB-04) and their status history attached — everything the export must not carry and
 * would pay for on every row. This reads the flat columns the mart asks for and nothing else, which is
 * also why the page is a projection rather than a list of {@code Message}.
 *
 * <p>The attempt count is a correlated subquery over {@code delivery_attempt} rather than a join: at one
 * row per attempt a join would multiply the page, and the mart wants the number, not the attempts.
 */
@Repository
public class EventExportPersistenceAdapter implements EventExportRepository {

    private static final String SELECT_CURSOR =
            "SELECT name, position, last_id, updated_at FROM export_cursor WHERE name = :name";

    private static final String UPSERT_CURSOR = """
            INSERT INTO export_cursor (name, position, last_id, updated_at)
            VALUES (:name, :position, :lastId, :updatedAt)
            ON CONFLICT (name) DO UPDATE
               SET position = EXCLUDED.position,
                   last_id = EXCLUDED.last_id,
                   updated_at = EXCLUDED.updated_at
            """;

    private static final String SELECT_TERMINAL = """
            SELECT m.id, m.stream_id, m.batch_id, m.traffic_class, m.selected_channel,
                   m.selected_provider_code, m.status, m.status_reason, m.segments,
                   m.cost, m.cost_currency, m.test, m.accepted_at, m.terminal_at,
                   (SELECT count(*) FROM delivery_attempt a WHERE a.message_id = m.id) AS attempts
              FROM message m
             WHERE m.terminal_at IS NOT NULL
               AND (m.terminal_at, m.id) > (CAST(:after AS timestamptz), CAST(:lastId AS uuid))
             ORDER BY m.terminal_at, m.id
             LIMIT :limit
            """;

    /**
     * Sentinel for the very first page.
     *
     * <p>The row comparison {@code (terminal_at, id) > (:after, :lastId)} needs both sides; before the
     * first export there is no last id, and the smallest possible UUID makes the comparison mean "from
     * this instant onwards, whatever the id".
     */
    private static final UUID NO_LAST_ID = new UUID(0L, 0L);

    private final JdbcClient jdbcClient;

    public EventExportPersistenceAdapter(JdbcClient jdbcClient) {
        this.jdbcClient = Guard.notNull(jdbcClient, "jdbcClient");
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ExportCursor> findCursor(String name) {
        return jdbcClient
                .sql(SELECT_CURSOR)
                .param("name", name)
                .query((ResultSet rs, int rowNum) -> new ExportCursor(
                        rs.getString("name"),
                        SqlValues.instant(rs, "position"),
                        messageId(rs),
                        SqlValues.instant(rs, "updated_at")))
                .optional();
    }

    @Override
    @Transactional
    public void saveCursor(ExportCursor cursor) {
        Guard.notNull(cursor, "cursor");
        jdbcClient
                .sql(UPSERT_CURSOR)
                .param("name", cursor.name())
                .param("position", SqlValues.timestamp(cursor.position()))
                .param(
                        "lastId",
                        cursor.lastMessageId() == null
                                ? null
                                : cursor.lastMessageId().value())
                .param("updatedAt", SqlValues.timestamp(cursor.updatedAt()))
                .update();
    }

    @Override
    @Transactional(readOnly = true)
    public List<DeliveryEvent> findTerminalAfter(Instant after, MessageId lastMessageId, int limit) {
        Guard.notNull(after, "after");
        Guard.positive(limit, "limit");
        return jdbcClient
                .sql(SELECT_TERMINAL)
                .param("after", SqlValues.timestamp(after))
                .param("lastId", lastMessageId == null ? NO_LAST_ID : lastMessageId.value())
                .param("limit", limit)
                .query(EventExportPersistenceAdapter::toEvent)
                .list();
    }

    private static DeliveryEvent toEvent(ResultSet rs, int rowNum) throws SQLException {
        UUID batchId = SqlValues.uuid(rs, "batch_id");
        String providerCode = rs.getString("selected_provider_code");
        return new DeliveryEvent(
                new MessageId(SqlValues.uuid(rs, "id")),
                StreamId.of(rs.getString("stream_id")),
                batchId == null ? null : new BatchId(batchId),
                SqlValues.enumValue(rs, "traffic_class", TrafficClass.class),
                SqlValues.enumValue(rs, "selected_channel", Channel.class),
                providerCode == null ? null : ProviderCode.of(providerCode),
                outcomeOf(rs),
                rs.getBoolean("test"));
    }

    private static DeliveryOutcome outcomeOf(ResultSet rs) throws SQLException {
        return new DeliveryOutcome(
                SqlValues.enumValue(rs, "status", MessageStatus.class),
                SqlValues.enumValue(rs, "status_reason", RejectionReason.class),
                rs.getInt("segments"),
                SqlValues.money(rs, "cost", "cost_currency"),
                rs.getInt("attempts"),
                SqlValues.instant(rs, "accepted_at"),
                SqlValues.instant(rs, "terminal_at"));
    }

    private static MessageId messageId(ResultSet rs) throws SQLException {
        UUID id = SqlValues.uuid(rs, "last_id");
        return id == null ? null : new MessageId(id);
    }
}
