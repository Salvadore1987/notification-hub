package uz.hamkorbank.commhub.adapter.out.persistence.audit;

import java.sql.ResultSet;
import java.sql.SQLException;
import org.springframework.jdbc.core.RowMapper;
import uz.hamkorbank.commhub.adapter.out.persistence.support.SqlValues;
import uz.hamkorbank.commhub.application.port.out.AuditEntry;
import uz.hamkorbank.commhub.domain.model.Actor;
import uz.hamkorbank.commhub.domain.model.type.ActorType;

/**
 * A row of {@code audit_log} back into the entry the journal was written from (FR-7.3).
 *
 * <p>The actor is reconstructed from the stored name alone: the type is not a column, because what an
 * audit reader needs is who — a login, a provider code, or the word {@code SYSTEM} the Hub writes for its
 * own actions. A name matching one of the {@link ActorType} constants is read back as that type, and
 * everything else is a person; nothing downstream branches on it beyond how it is displayed.
 */
class AuditEntryRowMapper implements RowMapper<AuditEntry> {

    @Override
    public AuditEntry mapRow(ResultSet rs, int rowNum) throws SQLException {
        String username = rs.getString("username");
        return new AuditEntry(
                actorOf(username),
                rs.getString("action"),
                rs.getString("entity_type"),
                rs.getString("entity_id"),
                AuditEntry.Change.of(rs.getString("before_state"), rs.getString("after_state")),
                rs.getString("ip"),
                rs.getString("reason"),
                SqlValues.instant(rs, "occurred_at"));
    }

    private static Actor actorOf(String username) {
        if (username == null || username.isBlank() || ActorType.SYSTEM.name().equals(username)) {
            return Actor.system();
        }
        return Actor.operator(username);
    }
}
