package uz.hamkorbank.commhub.adapter.out.persistence.delivery;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import uz.hamkorbank.commhub.adapter.out.persistence.support.SqlValues;
import uz.hamkorbank.commhub.application.port.out.PushDelivery;
import uz.hamkorbank.commhub.application.port.out.PushDeliveryLogPort;
import uz.hamkorbank.commhub.domain.model.type.AttemptResult;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.PushPlatform;
import uz.hamkorbank.commhub.domain.model.vo.AdapterType;
import uz.hamkorbank.commhub.domain.model.vo.AddressHash;
import uz.hamkorbank.commhub.domain.model.vo.AttemptId;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.model.vo.ProviderId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderMessageId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderRef;
import uz.hamkorbank.commhub.domain.support.UuidV7;

/**
 * {@link PushDeliveryLogPort} over the partitioned {@code push_delivery} table (PU-09).
 *
 * <p>{@link Propagation#MANDATORY} on the write, like the outbox: these rows describe an attempt of the
 * sending saga, and a device row that survived a rolled-back attempt would claim a call the message has
 * no record of.
 *
 * <p>Written in one batch — a recipient with four devices is four rows and four round trips would be
 * three too many on a path that runs once per push (PU-10).
 *
 * <p>The provider is stored whole (id, code, adapter type) for the same reason {@code delivery_attempt}
 * stores it whole: the row has to stay readable after the provider it names has been renamed or removed
 * from the registry.
 */
@Repository
public class PushDeliveryPersistenceAdapter implements PushDeliveryLogPort {

    private static final String INSERT = """
            INSERT INTO push_delivery (
                id, message_id, request_at, attempt_id, provider_id, provider_code, provider_adapter_type,
                token_hash, platform, provider_message_id, result, response_code, error_description,
                token_invalidated)
            VALUES (
                :id, :messageId, :requestAt, :attemptId, :providerId, :providerCode, :adapterType,
                :tokenHash, :platform, :providerMessageId, :result, :responseCode, :errorDescription,
                :tokenInvalidated)
            """;

    private static final String FIND_BY_MESSAGE = """
            SELECT id, message_id, request_at, attempt_id, provider_id, provider_code, provider_adapter_type,
                   token_hash, platform, provider_message_id, result, response_code, error_description,
                   token_invalidated
            FROM push_delivery
            WHERE message_id = :messageId
            ORDER BY request_at, id
            """;

    /** {@code push_delivery.error_description} is {@code varchar(1024)}. */
    private static final int MAX_ERROR_LENGTH = 1024;

    private final JdbcClient jdbcClient;

    public PushDeliveryPersistenceAdapter(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(List<PushDelivery> deliveries) {
        if (deliveries == null || deliveries.isEmpty()) {
            return;
        }
        deliveries.forEach(this::insert);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PushDelivery> findByMessage(MessageId messageId) {
        return jdbcClient
                .sql(FIND_BY_MESSAGE)
                .param("messageId", messageId.value())
                .query((rs, rowNum) -> new PushDelivery(
                        MessageId.of(SqlValues.uuid(rs, "message_id")),
                        AttemptId.of(SqlValues.uuid(rs, "attempt_id")),
                        new ProviderRef(
                                ProviderId.of(SqlValues.uuid(rs, "provider_id")),
                                ProviderCode.of(rs.getString("provider_code")),
                                Channel.PUSH,
                                AdapterType.of(rs.getString("provider_adapter_type"))),
                        new AddressHash(rs.getString("token_hash")),
                        SqlValues.enumValue(rs, "platform", PushPlatform.class),
                        providerMessageId(rs.getString("provider_message_id")),
                        new PushDelivery.Outcome(
                                SqlValues.enumValue(rs, "result", AttemptResult.class),
                                rs.getString("response_code"),
                                rs.getString("error_description"),
                                rs.getBoolean("token_invalidated"),
                                SqlValues.instant(rs, "request_at"))))
                .list();
    }

    private void insert(PushDelivery delivery) {
        PushDelivery.Outcome outcome = delivery.outcome();
        jdbcClient
                .sql(INSERT)
                .param("id", UuidV7.generate())
                .param("messageId", delivery.messageId().value())
                .param("requestAt", SqlValues.timestamp(outcome.respondedAt()))
                .param("attemptId", delivery.attemptId().value())
                .param("providerId", delivery.provider().id().value())
                .param("providerCode", delivery.provider().code().value())
                .param("adapterType", delivery.provider().adapterType().value())
                .param("tokenHash", delivery.tokenHash().value())
                .param("platform", delivery.platform().name())
                .param(
                        "providerMessageId",
                        delivery.providerMessageIdOptional()
                                .map(ProviderMessageId::value)
                                .orElse(null))
                .param("result", outcome.result().name())
                .param("responseCode", outcome.responseCode())
                .param("errorDescription", truncate(outcome.errorDescription()))
                .param("tokenInvalidated", outcome.tokenInvalidated())
                .update();
    }

    private static ProviderMessageId providerMessageId(String value) {
        return value == null || value.isBlank() ? null : ProviderMessageId.of(value);
    }

    private static String truncate(String description) {
        if (description == null) {
            return null;
        }
        return description.length() <= MAX_ERROR_LENGTH ? description : description.substring(0, MAX_ERROR_LENGTH);
    }
}
