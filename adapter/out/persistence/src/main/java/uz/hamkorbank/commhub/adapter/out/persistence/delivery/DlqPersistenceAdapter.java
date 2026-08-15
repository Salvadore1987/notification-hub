package uz.hamkorbank.commhub.adapter.out.persistence.delivery;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import uz.hamkorbank.commhub.adapter.out.persistence.support.SqlValues;
import uz.hamkorbank.commhub.application.port.in.query.DlqQuery;
import uz.hamkorbank.commhub.application.port.out.DlqRepository;
import uz.hamkorbank.commhub.domain.model.DlqEntry;
import uz.hamkorbank.commhub.domain.model.type.RejectionReason;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;

/** {@link DlqRepository} over {@code dlq_entry} (FR-3.3, FR-6.4). */
@Repository
public class DlqPersistenceAdapter implements DlqRepository {

    private static final String SELECT =
            "SELECT message_id, reason, last_error, moved_at, retried_by, retried_at, archived FROM dlq_entry";

    /**
     * Filters of the DLQ screen, written once so the page and its count cannot disagree (UI-03).
     *
     * <p>With both flags off the predicate is the one {@code dlq_entry_pending_idx} was built for (V5),
     * which is the default page and the one that is asked for constantly.
     */
    private static final String WHERE = """
             WHERE (CAST(:from AS timestamptz) IS NULL OR moved_at >= :from)
               AND (CAST(:to AS timestamptz) IS NULL OR moved_at < :to)
               AND (CAST(:reason AS varchar) IS NULL OR reason = :reason)
               AND (CAST(:includeRetried AS boolean) OR retried_at IS NULL)
               AND (CAST(:includeArchived AS boolean) OR NOT archived)
            """;

    /**
     * One row per message, so a message that comes back to the DLQ overwrites its own entry.
     *
     * <p>{@code moved_at} is updated with the rest of the columns, and that is the whole point of the
     * list being complete. A message reaches this row twice only after a manual retry (ST-02), and the
     * second arrival resets {@code retried_by}/{@code retried_at} — the entry becomes retryable again,
     * because the operator who fixed the provider is entitled to a second attempt. Keeping the first
     * arrival's {@code moved_at} while clearing the retry stamp described a landing that never happened:
     * the screen sorts and filters by {@code moved_at} (its period filter is the operator's only way to
     * narrow the list), so a message that failed again today stayed under yesterday's timestamp and
     * dropped out of the very window somebody was looking in for it.
     */
    private static final String UPSERT = """
            INSERT INTO dlq_entry (message_id, reason, last_error, moved_at, retried_by, retried_at, archived)
            VALUES (:messageId, :reason, :lastError, :movedAt, :retriedBy, :retriedAt, :archived)
            ON CONFLICT (message_id) DO UPDATE SET
                reason = EXCLUDED.reason,
                last_error = EXCLUDED.last_error,
                moved_at = EXCLUDED.moved_at,
                retried_by = EXCLUDED.retried_by,
                retried_at = EXCLUDED.retried_at,
                archived = EXCLUDED.archived
            """;

    private final JdbcClient jdbcClient;

    public DlqPersistenceAdapter(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    @Transactional
    public DlqEntry save(DlqEntry entry) {
        jdbcClient
                .sql(UPSERT)
                .param("messageId", entry.messageId().value())
                .param("reason", entry.reason().name())
                .param("lastError", entry.lastError().orElse(null))
                .param("movedAt", SqlValues.timestamp(entry.movedAt()))
                .param("retriedBy", entry.retriedBy().orElse(null))
                .param("retriedAt", SqlValues.timestamp(entry.retriedAt().orElse(null)))
                .param("archived", entry.isArchived())
                .update();
        return entry;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DlqEntry> findByMessageId(MessageId messageId) {
        return jdbcClient
                .sql(SELECT + " WHERE message_id = :messageId")
                .param("messageId", messageId.value())
                .query(rowMapper())
                .optional();
    }

    @Override
    @Transactional(readOnly = true)
    public List<DlqEntry> findRetryable(int limit) {
        return jdbcClient
                .sql(SELECT + " WHERE NOT archived AND retried_at IS NULL ORDER BY moved_at LIMIT :limit")
                .param("limit", limit)
                .query(rowMapper())
                .list();
    }

    @Override
    @Transactional(readOnly = true)
    public List<DlqEntry> search(DlqQuery query) {
        return bind(jdbcClient.sql(SELECT + WHERE + " ORDER BY moved_at LIMIT :limit OFFSET :offset"), query)
                .param("limit", query.limit())
                .param("offset", query.offset())
                .query(rowMapper())
                .list();
    }

    @Override
    @Transactional(readOnly = true)
    public long count(DlqQuery query) {
        return bind(jdbcClient.sql("SELECT count(*) FROM dlq_entry" + WHERE), query)
                .query(Long.class)
                .single();
    }

    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec statement, DlqQuery query) {
        return statement
                .param("from", SqlValues.timestamp(query.from()))
                .param("to", SqlValues.timestamp(query.to()))
                .param("reason", SqlValues.nameOf(query.reason()))
                .param("includeRetried", query.includeRetried())
                .param("includeArchived", query.includeArchived());
    }

    private RowMapper<DlqEntry> rowMapper() {
        return (rs, rowNum) -> {
            DlqEntry entry = DlqEntry.of(
                    MessageId.of(SqlValues.uuid(rs, "message_id")),
                    SqlValues.enumValue(rs, "reason", RejectionReason.class),
                    rs.getString("last_error"),
                    SqlValues.instant(rs, "moved_at"));
            Instant retriedAt = SqlValues.instant(rs, "retried_at");
            if (retriedAt != null) {
                entry.retry(rs.getString("retried_by"), retriedAt);
            }
            if (rs.getBoolean("archived")) {
                entry.archive();
            }
            return entry;
        };
    }
}
