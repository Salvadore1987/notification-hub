package uz.hamkorbank.commhub.adapter.out.persistence.audit;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import uz.hamkorbank.commhub.adapter.out.persistence.support.SqlValues;
import uz.hamkorbank.commhub.application.port.out.AuditEntry;
import uz.hamkorbank.commhub.application.port.out.AuditPort;
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
public class AuditPersistenceAdapter implements AuditPort {

    private static final String INSERT = """
            INSERT INTO audit_log (id, user_id, username, action, entity_type, entity_id,
                                   before_state, after_state, ip, occurred_at)
            VALUES (:id,
                    (SELECT u.id FROM app_user u WHERE u.username = :username),
                    :username, :action, :entityType, :entityId,
                    to_jsonb(CAST(:before AS text)), to_jsonb(CAST(:after AS text)),
                    CAST(:ip AS inet), :occurredAt)
            """;

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
}
