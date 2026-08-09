package uz.hamkorbank.commhub.adapter.out.persistence.messaging;

import java.sql.ResultSet;
import java.sql.SQLException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import uz.hamkorbank.commhub.adapter.out.persistence.support.SqlValues;
import uz.hamkorbank.commhub.domain.model.Actor;
import uz.hamkorbank.commhub.domain.model.StatusChange;
import uz.hamkorbank.commhub.domain.model.type.ActorType;
import uz.hamkorbank.commhub.domain.model.type.MessageStatus;
import uz.hamkorbank.commhub.domain.model.type.RejectionReason;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;

/** Rebuilds one {@code message_status_history} row as a {@link StatusChange} of its message (ST-01). */
@Component
public class StatusChangeRowMapper implements RowMapper<StatusChangeRowMapper.OwnedStatusChange> {

    @Override
    public OwnedStatusChange mapRow(ResultSet rs, int rowNum) throws SQLException {
        String providerCode = rs.getString("provider_code");
        StatusChange change = new StatusChange(
                SqlValues.enumValue(rs, "status", MessageStatus.class),
                SqlValues.enumValue(rs, "reason", RejectionReason.class),
                rs.getString("details"),
                new Actor(SqlValues.enumValue(rs, "actor_type", ActorType.class), rs.getString("actor_id")),
                providerCode == null ? null : ProviderCode.of(providerCode),
                SqlValues.instant(rs, "occurred_at"));
        return new OwnedStatusChange(MessageId.of(SqlValues.uuid(rs, "message_id")), change);
    }

    /** A status change together with the message it belongs to, so a page of rows can be grouped. */
    public record OwnedStatusChange(MessageId messageId, StatusChange change) {}
}
