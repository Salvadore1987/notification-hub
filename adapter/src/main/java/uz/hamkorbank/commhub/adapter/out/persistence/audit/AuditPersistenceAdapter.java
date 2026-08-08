package uz.hamkorbank.commhub.adapter.out.persistence.audit;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import uz.hamkorbank.commhub.adapter.out.persistence.support.SqlValues;
import uz.hamkorbank.commhub.application.port.in.query.AuditQuery;
import uz.hamkorbank.commhub.application.port.out.AuditEntry;
import uz.hamkorbank.commhub.application.port.out.AuditPort;
import uz.hamkorbank.commhub.application.port.out.AuditQueryPort;
import uz.hamkorbank.commhub.domain.support.Guard;
import uz.hamkorbank.commhub.domain.support.UuidV7;

/**
 * {@link AuditPort} over the append-only {@code audit_log} (FR-7.3).
 *
 * <p>The actor is written by name as well as by id: an audit record has to stay readable after the user
 * row it points at is gone, which is also why the foreign key is {@code ON DELETE SET NULL}.
 *
 * <p>The user id is resolved from the actor's login in the same statement; an actor the user table does
 * not know — the system itself, a provider callback — simply leaves it null.
 *
 * <p>{@code before_state}/{@code after_state} are {@code jsonb} columns and the port hands over rendered
 * text, so the value is wrapped with {@code to_jsonb} into a JSON string rather than cast: casting
 * {@code "status=ACTIVE, strategy=WEIGHTED"} to {@code jsonb} fails, and an audit write that throws would
 * roll back the very change it was journalling. {@code to_jsonb} is strict, so a null state stays null.
 */
@Repository
public class AuditPersistenceAdapter implements AuditPort, AuditQueryPort {

    private static final String INSERT = """
            INSERT INTO audit_log (id, user_id, username, action, entity_type, entity_id,
                                   before_state, after_state, ip, occurred_at)
            VALUES (:id,
                    (SELECT u.id FROM app_user u WHERE u.username = :username),
                    :username, :action, :entityType, :entityId,
                    to_jsonb(CAST(:before AS text)), to_jsonb(CAST(:after AS text)),
                    CAST(:ip AS inet), :occurredAt)
            """;

    /**
     * Filters applied as {@code :name IS NULL OR column = :name} so one statement covers every combination.
     *
     * <p>Ordered newest first and read through {@code audit_log_occurred_idx}; the entity filter has its own
     * index, which is the one the "everything that happened to this provider" question uses (FR-7.3).
     */
    private static final String WHERE = """
             WHERE (CAST(:from AS timestamptz) IS NULL OR a.occurred_at >= CAST(:from AS timestamptz))
               AND (CAST(:to   AS timestamptz) IS NULL OR a.occurred_at <= CAST(:to   AS timestamptz))
               AND (CAST(:username   AS text) IS NULL OR a.username    = CAST(:username   AS text))
               AND (CAST(:action     AS text) IS NULL OR a.action      = CAST(:action     AS text))
               AND (CAST(:entityType AS text) IS NULL OR a.entity_type = CAST(:entityType AS text))
               AND (CAST(:entityId   AS text) IS NULL OR a.entity_id   = CAST(:entityId   AS text))
            """;

    private static final String SELECT = """
            SELECT a.username, a.action, a.entity_type, a.entity_id,
                   a.before_state #>> '{}' AS before_state,
                   a.after_state  #>> '{}' AS after_state,
                   host(a.ip) AS ip, a.occurred_at
              FROM audit_log a
            """ + WHERE + """
             ORDER BY a.occurred_at DESC
             LIMIT :limit OFFSET :offset
            """;

    private static final String COUNT = "SELECT count(*) FROM audit_log a" + WHERE;

    private final JdbcClient jdbcClient;

    public AuditPersistenceAdapter(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    @Transactional
    public void write(AuditEntry entry) {
        jdbcClient
                .sql(INSERT)
                .param("id", UuidV7.generate())
                .param("username", usernameOf(entry))
                .param("action", entry.action())
                .param("entityType", entry.entityType())
                .param("entityId", entry.entityId())
                .param("before", entry.before())
                .param("after", entry.after())
                .param("ip", entry.sourceIp())
                .param("occurredAt", SqlValues.timestamp(entry.occurredAt()))
                .update();
    }

    private String usernameOf(AuditEntry entry) {
        return entry.actor().id() == null
                ? entry.actor().type().name()
                : entry.actor().id();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AuditEntry> search(AuditQuery query) {
        Guard.notNull(query, "query");
        return bind(jdbcClient.sql(SELECT), query)
                .param("limit", query.limit())
                .param("offset", query.offset())
                .query(new AuditEntryRowMapper())
                .list();
    }

    @Override
    @Transactional(readOnly = true)
    public long count(AuditQuery query) {
        Guard.notNull(query, "query");
        return bind(jdbcClient.sql(COUNT), query).query(Long.class).optional().orElse(0L);
    }

    /**
     * Binds the optional filters of {@link AuditQuery}.
     *
     * <p>All six are bound even when null: the SQL applies each with an {@code IS NULL OR} test, so one
     * statement serves every combination and the planner sees one prepared statement rather than sixty-four.
     */
    private static StatementSpec bind(StatementSpec statement, AuditQuery query) {
        return statement
                .param("from", SqlValues.timestamp(query.from()))
                .param("to", SqlValues.timestamp(query.to()))
                .param("username", query.username())
                .param("action", query.action())
                .param("entityType", query.entityType())
                .param("entityId", query.entityId());
    }
}
