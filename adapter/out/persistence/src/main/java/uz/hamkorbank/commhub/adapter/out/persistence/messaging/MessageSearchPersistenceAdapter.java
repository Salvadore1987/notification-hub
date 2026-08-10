package uz.hamkorbank.commhub.adapter.out.persistence.messaging;

import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import uz.hamkorbank.commhub.adapter.out.persistence.support.AnalyticsJdbcClient;
import uz.hamkorbank.commhub.adapter.out.persistence.support.SqlValues;
import uz.hamkorbank.commhub.application.port.in.query.MessageSearchQuery;
import uz.hamkorbank.commhub.application.port.out.MessageDigest;
import uz.hamkorbank.commhub.application.port.out.MessageSearchPort;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.MessageStatus;
import uz.hamkorbank.commhub.domain.model.type.RejectionReason;
import uz.hamkorbank.commhub.domain.model.vo.BatchId;
import uz.hamkorbank.commhub.domain.model.vo.CorrelationId;
import uz.hamkorbank.commhub.domain.model.vo.ExternalMessageId;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * {@link MessageSearchPort} over {@code message} (§11.2 "Сообщения", UI-03, DB-06).
 *
 * <p>Reads rows, never aggregates. Fifty lines of a list would otherwise mean fifty
 * {@code Message} rehydrations, each decrypting a message body (DB-04) that the list does not show.
 *
 * <p>The period is always in the predicate and always first, because it is the partition key: the
 * planner prunes on it, and a search without it reads every partition retention has kept (DB-02). The
 * query record makes the period mandatory for exactly this reason, so there is no branch here for its
 * absence.
 *
 * <p>The recipient is matched against the extracted {@code jsonb} field rather than the whole document,
 * against the expression indexes V14 adds. Two of them, one per addressable channel, because the two
 * questions are asked differently: a number is matched as it is stored — every accepted MSISDN is
 * already normalised to {@code 9989xxxxxxxx} — and a mailbox case-insensitively, which is what
 * {@code EmailAddress} normalisation means once the address is in the column. Push tokens are not
 * searchable and deliberately so: an operator does not have one, and the device question is asked
 * through {@code push_delivery} under the message that was sent.
 */
@Repository
public class MessageSearchPersistenceAdapter implements MessageSearchPort {

    private static final String SELECT = """
            SELECT id, accepted_at, external_id, stream_id, batch_id, correlation_id,
                   recipient ->> 'msisdn'  AS recipient_msisdn,
                   recipient ->> 'email'   AS recipient_email,
                   channel_plan -> 'channels' ->> 0 AS requested_channel,
                   status, status_reason, selected_channel, selected_provider_code,
                   segments, cost, cost_currency, terminal_at
            FROM message
            """;

    private static final String COUNT = "SELECT count(*) FROM message";

    /**
     * The filters, in one predicate shared by the page and its count.
     *
     * <p>Written once because the two must agree: a count computed by a second predicate is a paging
     * control that promises a page which is not there.
     */
    private static final String WHERE = """
             WHERE accepted_at >= :from
               AND accepted_at < :to
               AND (CAST(:streamId AS varchar) IS NULL OR stream_id = :streamId)
               AND (CAST(:status AS varchar) IS NULL OR status = :status)
               AND (CAST(:externalMessageId AS varchar) IS NULL OR external_id = :externalMessageId)
               AND (CAST(:correlationId AS varchar) IS NULL OR correlation_id = :correlationId)
               AND (CAST(:batchId AS uuid) IS NULL OR batch_id = :batchId)
               AND (CAST(:recipient AS varchar) IS NULL
                    OR recipient ->> 'msisdn' = :recipient
                    OR lower(recipient ->> 'email') = :recipientLower)
            """;

    private final AnalyticsJdbcClient jdbcClient;

    public MessageSearchPersistenceAdapter(AnalyticsJdbcClient jdbcClient) {
        this.jdbcClient = Guard.notNull(jdbcClient, "jdbcClient");
    }

    @Override
    @Transactional(readOnly = true)
    public List<MessageDigest> search(MessageSearchQuery query) {
        return bind(
                        jdbcClient
                                .client()
                                .sql(SELECT + WHERE + " ORDER BY accepted_at DESC, id LIMIT :limit OFFSET :offset"),
                        query)
                .param("limit", query.limit())
                .param("offset", query.offset())
                .query(rowMapper())
                .list();
    }

    @Override
    @Transactional(readOnly = true)
    public long count(MessageSearchQuery query) {
        return bind(jdbcClient.client().sql(COUNT + WHERE), query)
                .query(Long.class)
                .single();
    }

    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec statement, MessageSearchQuery query) {
        MessageSearchQuery.MessageFilter filter = query.filter();
        String recipient = filter.recipient();
        return statement
                .param("from", SqlValues.timestamp(query.from()))
                .param("to", SqlValues.timestamp(query.to()))
                .param(
                        "streamId",
                        query.streamId() == null ? null : query.streamId().value())
                .param("status", SqlValues.nameOf(query.status()))
                .param("externalMessageId", filter.externalMessageId())
                .param("correlationId", filter.correlationId())
                .param("batchId", filter.batchId())
                .param("recipient", recipient)
                .param("recipientLower", recipient == null ? null : recipient.toLowerCase(Locale.ROOT));
    }

    private static RowMapper<MessageDigest> rowMapper() {
        return (rs, rowNum) -> new MessageDigest(
                MessageId.of(SqlValues.uuid(rs, "id")),
                StreamId.of(rs.getString("stream_id")),
                ExternalMessageId.of(rs.getString("external_id")),
                SqlValues.enumValue(rs, "requested_channel", Channel.class),
                SqlValues.enumValue(rs, "status", MessageStatus.class),
                recipientOf(rs.getString("recipient_msisdn"), rs.getString("recipient_email")),
                SqlValues.instant(rs, "accepted_at"),
                new MessageDigest.Routing(
                        providerCode(rs.getString("selected_provider_code")),
                        SqlValues.enumValue(rs, "selected_channel", Channel.class),
                        SqlValues.enumValue(rs, "status_reason", RejectionReason.class),
                        batchId(SqlValues.uuid(rs, "batch_id")),
                        CorrelationId.of(rs.getString("correlation_id")),
                        SqlValues.money(rs, "cost", "cost_currency"),
                        rs.getInt("segments"),
                        SqlValues.instant(rs, "terminal_at")));
    }

    /**
     * The one address the row is shown by.
     *
     * <p>A message carries a recipient, not a channel address: the same submission may name a number and
     * a mailbox (MP-02). The list shows the one the requested channel would use, and where both are
     * present the number comes first, because that is the column an operator is searching by.
     */
    private static String recipientOf(String msisdn, String email) {
        return msisdn != null ? msisdn : email;
    }

    private static ProviderCode providerCode(String value) {
        return value == null ? null : ProviderCode.of(value);
    }

    private static BatchId batchId(java.util.UUID value) {
        return value == null ? null : BatchId.of(value);
    }
}
