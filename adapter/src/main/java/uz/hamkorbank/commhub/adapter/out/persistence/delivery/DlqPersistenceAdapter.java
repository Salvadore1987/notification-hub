package uz.hamkorbank.commhub.adapter.out.persistence.delivery;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import uz.hamkorbank.commhub.adapter.out.persistence.support.SqlValues;
import uz.hamkorbank.commhub.application.port.out.DlqRepository;
import uz.hamkorbank.commhub.domain.model.DlqEntry;
import uz.hamkorbank.commhub.domain.model.type.RejectionReason;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;

/** {@link DlqRepository} over {@code dlq_entry} (FR-3.3, FR-6.4). */
@Repository
public class DlqPersistenceAdapter implements DlqRepository {

    private static final String SELECT =
            "SELECT message_id, reason, last_error, moved_at, retried_by, retried_at, archived FROM dlq_entry";

    private static final String UPSERT = """
            INSERT INTO dlq_entry (message_id, reason, last_error, moved_at, retried_by, retried_at, archived)
            VALUES (:messageId, :reason, :lastError, :movedAt, :retriedBy, :retriedAt, :archived)
            ON CONFLICT (message_id) DO UPDATE SET
                reason = EXCLUDED.reason,
                last_error = EXCLUDED.last_error,
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
