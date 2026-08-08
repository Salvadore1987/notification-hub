package uz.hamkorbank.commhub.adapter.out.persistence.messaging;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import uz.hamkorbank.commhub.adapter.out.persistence.support.SqlValues;
import uz.hamkorbank.commhub.domain.model.DeliveryAttempt;
import uz.hamkorbank.commhub.domain.model.type.AttemptResult;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.ErrorClass;
import uz.hamkorbank.commhub.domain.model.vo.AdapterType;
import uz.hamkorbank.commhub.domain.model.vo.AttemptId;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.model.vo.ProviderId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderMessageId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderRef;

/**
 * Rebuilds a {@link DeliveryAttempt} from a {@code delivery_attempt} row.
 *
 * <p>An attempt has no rehydration builder because its lifecycle is a single step: it is opened and
 * then closed exactly once. Replaying that one step reproduces the stored row faithfully, with one
 * documented exception — a {@code TIMEOUT} carries the fixed description the domain assigns, since a
 * timeout has no provider response to preserve.
 */
@Component
public class DeliveryAttemptRowMapper implements RowMapper<DeliveryAttemptRowMapper.OwnedAttempt> {

    @Override
    public OwnedAttempt mapRow(ResultSet rs, int rowNum) throws SQLException {
        MessageId messageId = MessageId.of(SqlValues.uuid(rs, "message_id"));
        String providerMessageId = rs.getString("provider_message_id");
        DeliveryAttempt attempt = DeliveryAttempt.start(
                AttemptId.of(SqlValues.uuid(rs, "id")),
                messageId,
                providerRef(rs),
                rs.getInt("attempt_no"),
                providerMessageId == null ? null : ProviderMessageId.of(providerMessageId),
                SqlValues.instant(rs, "request_at"));
        complete(attempt, rs);
        return new OwnedAttempt(messageId, attempt);
    }

    private ProviderRef providerRef(ResultSet rs) throws SQLException {
        return new ProviderRef(
                ProviderId.of(SqlValues.uuid(rs, "provider_id")),
                ProviderCode.of(rs.getString("provider_code")),
                SqlValues.enumValue(rs, "provider_channel", Channel.class),
                AdapterType.of(rs.getString("provider_adapter_type")));
    }

    private void complete(DeliveryAttempt attempt, ResultSet rs) throws SQLException {
        AttemptResult result = SqlValues.enumValue(rs, "result", AttemptResult.class);
        if (result == AttemptResult.PENDING) {
            return;
        }
        Instant responseAt = SqlValues.instant(rs, "response_at");
        String responseCode = rs.getString("response_code");
        String description = rs.getString("error_description");
        switch (result) {
            case ACCEPTED -> attempt.succeed(responseCode, null, responseAt);
            case REJECTED -> attempt.reject(responseCode, description, responseAt);
            case ERROR ->
                attempt.fail(
                        responseCode,
                        SqlValues.enumValue(rs, "error_class", ErrorClass.class),
                        description,
                        responseAt);
            case TIMEOUT -> attempt.timeout(responseAt);
            default -> throw new IllegalStateException("unknown attempt result " + result);
        }
    }

    /** An attempt together with the message it belongs to, so a page of rows can be grouped. */
    public record OwnedAttempt(MessageId messageId, DeliveryAttempt attempt) {}
}
