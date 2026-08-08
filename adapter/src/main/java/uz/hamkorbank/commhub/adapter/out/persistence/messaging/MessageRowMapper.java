package uz.hamkorbank.commhub.adapter.out.persistence.messaging;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import uz.hamkorbank.commhub.adapter.out.persistence.crypto.ContentCodec;
import uz.hamkorbank.commhub.adapter.out.persistence.json.ChannelPlanJson;
import uz.hamkorbank.commhub.adapter.out.persistence.json.MessageContentsJson;
import uz.hamkorbank.commhub.adapter.out.persistence.json.RecipientJson;
import uz.hamkorbank.commhub.adapter.out.persistence.json.TimingJson;
import uz.hamkorbank.commhub.adapter.out.persistence.support.JsonCodec;
import uz.hamkorbank.commhub.adapter.out.persistence.support.SqlValues;
import uz.hamkorbank.commhub.domain.model.DeliveryAttempt;
import uz.hamkorbank.commhub.domain.model.Message;
import uz.hamkorbank.commhub.domain.model.MessageEnvelope;
import uz.hamkorbank.commhub.domain.model.StatusChange;
import uz.hamkorbank.commhub.domain.model.TemplateRef;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.ContentLocale;
import uz.hamkorbank.commhub.domain.model.type.MessageStatus;
import uz.hamkorbank.commhub.domain.model.type.Priority;
import uz.hamkorbank.commhub.domain.model.type.RejectionReason;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;
import uz.hamkorbank.commhub.domain.model.vo.AdapterType;
import uz.hamkorbank.commhub.domain.model.vo.BatchId;
import uz.hamkorbank.commhub.domain.model.vo.CorrelationId;
import uz.hamkorbank.commhub.domain.model.vo.DedupKey;
import uz.hamkorbank.commhub.domain.model.vo.ExternalMessageId;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.model.vo.ProviderId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderRef;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;
import uz.hamkorbank.commhub.domain.model.vo.TemplateCode;

/**
 * Rebuilds a {@link Message} from a {@code message} row plus its history and attempts.
 *
 * <p>Uses {@link Message#rehydrate} rather than the status machine: the stored status is the outcome of
 * transitions that were validated when they happened, and replaying them would rewrite the recorded
 * actors and provider codes of the history (ST-01).
 *
 * <p>The history and the attempts are not read here — they come from their own tables and are passed in
 * by {@link MessagePersistenceAdapter}, which loads them for a whole page of messages at once instead of
 * one query per row.
 *
 * <p>The content columns are read through {@link ContentCodec}, which decrypts what DB-04 stored
 * encrypted and passes a row written before that through unchanged.
 */
@Component
public class MessageRowMapper {

    private final JsonCodec jsonCodec;
    private final ContentCodec contentCodec;

    public MessageRowMapper(JsonCodec jsonCodec, ContentCodec contentCodec) {
        this.jsonCodec = jsonCodec;
        this.contentCodec = contentCodec;
    }

    /** Maps the {@code message} row alone; history and attempts are attached afterwards. */
    public RowMapper<PartialMessage> partialRowMapper() {
        return (rs, rowNum) -> new PartialMessage(MessageId.of(SqlValues.uuid(rs, "id")), builderOf(rs));
    }

    private Message.Rehydration builderOf(ResultSet rs) throws SQLException {
        Message.Rehydration rehydration = Message.rehydrate(
                envelope(rs),
                jsonCodec.read(rs.getString("recipient"), RecipientJson.class).toDomain(),
                jsonCodec
                        .read(rs.getString("channel_plan"), ChannelPlanJson.class)
                        .toDomain(),
                contentCodec
                        .read(rs.getString("contents"), MessageContentsJson.class)
                        .toDomain(),
                templateRef(rs),
                TimingJson.toDomain(jsonCodec.read(rs.getString("timing"), TimingJson.class)),
                SqlValues.instant(rs, "accepted_at"));
        rehydration
                .status(
                        SqlValues.enumValue(rs, "status", MessageStatus.class),
                        SqlValues.enumValue(rs, "status_reason", RejectionReason.class),
                        SqlValues.instant(rs, "terminal_at"))
                .route(SqlValues.enumValue(rs, "selected_channel", Channel.class), selectedProvider(rs))
                .billing(rs.getInt("segments"), SqlValues.money(rs, "cost", "cost_currency"))
                .test(rs.getBoolean("test"));
        UUID duplicateOf = SqlValues.uuid(rs, "duplicate_of");
        if (duplicateOf != null) {
            rehydration.duplicateOf(MessageId.of(duplicateOf));
        }
        return rehydration;
    }

    private MessageEnvelope envelope(ResultSet rs) throws SQLException {
        UUID batchId = SqlValues.uuid(rs, "batch_id");
        return new MessageEnvelope(
                MessageId.of(SqlValues.uuid(rs, "id")),
                ExternalMessageId.of(rs.getString("external_id")),
                StreamId.of(rs.getString("stream_id")),
                batchId == null ? null : BatchId.of(batchId),
                SqlValues.enumValue(rs, "traffic_class", TrafficClass.class),
                SqlValues.enumValue(rs, "priority", Priority.class),
                DedupKey.of(rs.getString("dedup_key")),
                CorrelationId.of(rs.getString("correlation_id")));
    }

    private TemplateRef templateRef(ResultSet rs) throws SQLException {
        String code = rs.getString("template_code");
        if (code == null) {
            return null;
        }
        return TemplateRef.of(
                TemplateCode.of(code),
                SqlValues.enumValue(rs, "template_locale", ContentLocale.class),
                contentCodec.readStringMap(rs.getString("template_variables")));
    }

    private ProviderRef selectedProvider(ResultSet rs) throws SQLException {
        UUID providerId = SqlValues.uuid(rs, "selected_provider_id");
        if (providerId == null) {
            return null;
        }
        return new ProviderRef(
                ProviderId.of(providerId),
                ProviderCode.of(rs.getString("selected_provider_code")),
                SqlValues.enumValue(rs, "selected_channel", Channel.class),
                AdapterType.of(rs.getString("selected_provider_adapter_type")));
    }

    /** A message row waiting for its history and attempts before it can become an aggregate. */
    public record PartialMessage(MessageId messageId, Message.Rehydration rehydration) {

        public Message complete(List<StatusChange> history, List<DeliveryAttempt> attempts) {
            return rehydration.statusHistory(history).attempts(attempts).build();
        }
    }
}
