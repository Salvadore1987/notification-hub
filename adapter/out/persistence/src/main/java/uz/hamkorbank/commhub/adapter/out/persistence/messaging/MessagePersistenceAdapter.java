package uz.hamkorbank.commhub.adapter.out.persistence.messaging;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import uz.hamkorbank.commhub.adapter.out.persistence.crypto.ContentCodec;
import uz.hamkorbank.commhub.adapter.out.persistence.json.ChannelPlanJson;
import uz.hamkorbank.commhub.adapter.out.persistence.json.MessageContentsJson;
import uz.hamkorbank.commhub.adapter.out.persistence.json.RecipientJson;
import uz.hamkorbank.commhub.adapter.out.persistence.json.TimingJson;
import uz.hamkorbank.commhub.adapter.out.persistence.messaging.MessageRowMapper.PartialMessage;
import uz.hamkorbank.commhub.adapter.out.persistence.support.JsonCodec;
import uz.hamkorbank.commhub.adapter.out.persistence.support.SqlValues;
import uz.hamkorbank.commhub.application.port.out.DispatchClaim;
import uz.hamkorbank.commhub.application.port.out.MessageRepository;
import uz.hamkorbank.commhub.domain.model.DeliveryAttempt;
import uz.hamkorbank.commhub.domain.model.Message;
import uz.hamkorbank.commhub.domain.model.StatusChange;
import uz.hamkorbank.commhub.domain.model.TemplateRef;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;
import uz.hamkorbank.commhub.domain.model.vo.BatchId;
import uz.hamkorbank.commhub.domain.model.vo.ExternalMessageId;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.model.vo.ProviderMessageId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderRef;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;
import uz.hamkorbank.commhub.domain.model.vo.TemplateVersionId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * {@link MessageRepository} over the partitioned {@code message} table with its history and attempts
 * (§10.1, DB-02).
 *
 * <p>A message is written as three statements in one transaction: the row itself, the status history
 * appended since the last save, and the delivery attempts. History rows carry no surrogate key — they
 * are identified by {@code (message_id, occurred_at, status)} and inserted with {@code DO NOTHING}, so
 * saving the same aggregate twice (an at-least-once redelivery, AD-03) appends nothing.
 *
 * <p>Queries that return several messages load the history and the attempts of the whole page in one
 * statement each; a per-row lookup would turn a batch of 500 into 1001 round trips.
 *
 * <p>{@code contents} and {@code template_variables} go through {@link ContentCodec} — they are stored
 * encrypted (DB-04). Everything the SQL here filters or orders by stays in clear, so the indexes of
 * DB-05 keep working.
 */
@Repository
public class MessagePersistenceAdapter implements MessageRepository {

    /** Statuses a message can still be picked up from by the dispatcher (ST-02). */
    private static final String DISPATCHABLE_STATUSES = "'ACCEPTED', 'VALIDATED', 'ROUTED', 'QUEUED', 'RETRYING'";

    private static final String NON_TERMINAL_STATUSES =
            "'ACCEPTED', 'VALIDATED', 'ROUTED', 'QUEUED', 'SENDING', 'SENT_TO_PROVIDER', 'RETRYING'";

    /**
     * Push is finished at {@code SENT_TO_PROVIDER} — no platform ever reports a delivery (PU-12).
     *
     * <p>The aggregate knows this ({@code Message.isTerminalForChannel}), and that is where the rule is
     * enforced; here it only keeps the TTL sweep from reading every delivered push on every pass, and
     * lets a push batch reach {@code COMPLETED} at all.
     */
    private static final String NOT_DELIVERED_PUSH = "NOT (status = 'SENT_TO_PROVIDER' AND selected_channel = 'PUSH')";

    /**
     * One pass of the dispatcher of a traffic class: take a page and stamp the lease on it (ADR-0039).
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} inside the CTE is what makes instances share the queue instead of
     * duplicating it — the outbox relay's pattern. The lease is written in the same statement, so a row is
     * never selected without being claimed, and it outlives this transaction because the provider call
     * happens outside it: a row lock alone would be gone the moment the claim commits.
     *
     * <p>Only ids come back. The turn that acts on a message loads the aggregate itself, in the
     * transaction that will save it.
     */
    private static final String CLAIM_DISPATCHABLE = """
            WITH claimed AS (
                SELECT id, accepted_at
                  FROM message
                 WHERE traffic_class = :trafficClass
                   AND status IN (%s)
                   AND (next_attempt_at IS NULL OR next_attempt_at <= :now)
                   AND (dispatch_claimed_until IS NULL OR dispatch_claimed_until <= :now)
                   AND (timing IS NULL
                        OR timing ->> 'sendAfter' IS NULL
                        OR (timing ->> 'sendAfter')::timestamptz <= :now)
                 ORDER BY accepted_at
                 LIMIT :limit
                   FOR UPDATE SKIP LOCKED
            )
            UPDATE message m
               SET dispatch_claimed_until = :leaseUntil,
                   dispatch_owner = :owner
              FROM claimed c
             WHERE m.id = c.id AND m.accepted_at = c.accepted_at
            RETURNING m.id
            """.formatted(DISPATCHABLE_STATUSES);

    /**
     * Gives the message back to the queue and says when it may be taken again (PR-01).
     *
     * <p>Deliberately not part of {@code UPSERT_MESSAGE}: the three scheduling columns are queue
     * bookkeeping rather than aggregate state, and every other save path — a provider report, the TTL
     * sweep — would otherwise wipe a lease it knows nothing about.
     */
    private static final String RELEASE_CLAIM = """
            UPDATE message
               SET dispatch_claimed_until = NULL,
                   dispatch_owner = NULL,
                   next_attempt_at = :nextAttemptAt
             WHERE id = :id AND accepted_at = :acceptedAt
            """;

    private static final String SELECT_MESSAGE = """
            SELECT id, accepted_at, external_id, stream_id, batch_id, traffic_class, priority, dedup_key,
                   correlation_id, recipient, channel_plan, contents, template_code, template_locale,
                   template_version_id, template_variables, timing, status, status_reason, selected_channel,
                   selected_provider_id, selected_provider_code, selected_provider_adapter_type, segments,
                   cost, cost_currency, duplicate_of, test, terminal_at
            FROM message
            """;

    private static final String UPSERT_MESSAGE = """
            INSERT INTO message (id, accepted_at, external_id, stream_id, batch_id, traffic_class, priority,
                                 dedup_key, correlation_id, recipient, channel_plan, contents, template_code,
                                 template_locale, template_version_id, template_variables, timing, status,
                                 status_reason, selected_channel, selected_provider_id, selected_provider_code,
                                 selected_provider_adapter_type, segments, cost, cost_currency, duplicate_of,
                                 test, terminal_at)
            VALUES (:id, :acceptedAt, :externalId, :streamId, :batchId, :trafficClass, :priority,
                    :dedupKey, :correlationId, CAST(:recipient AS jsonb), CAST(:channelPlan AS jsonb),
                    CAST(:contents AS jsonb), :templateCode, :templateLocale, :templateVersionId,
                    CAST(:templateVariables AS jsonb),
                    CAST(:timing AS jsonb), :status, :statusReason, :selectedChannel, :selectedProviderId,
                    :selectedProviderCode, :selectedProviderAdapterType, :segments, :cost, :costCurrency,
                    :duplicateOf, :test, :terminalAt)
            ON CONFLICT (id, accepted_at) DO UPDATE SET
                contents = EXCLUDED.contents,
                template_code = EXCLUDED.template_code,
                template_locale = EXCLUDED.template_locale,
                template_version_id = EXCLUDED.template_version_id,
                template_variables = EXCLUDED.template_variables,
                status = EXCLUDED.status,
                status_reason = EXCLUDED.status_reason,
                selected_channel = EXCLUDED.selected_channel,
                selected_provider_id = EXCLUDED.selected_provider_id,
                selected_provider_code = EXCLUDED.selected_provider_code,
                selected_provider_adapter_type = EXCLUDED.selected_provider_adapter_type,
                segments = EXCLUDED.segments,
                cost = EXCLUDED.cost,
                cost_currency = EXCLUDED.cost_currency,
                duplicate_of = EXCLUDED.duplicate_of,
                terminal_at = EXCLUDED.terminal_at,
                updated_at = now()
            """;

    private static final String INSERT_STATUS_CHANGE = """
            INSERT INTO message_status_history (message_id, occurred_at, status, reason, details,
                                                actor_type, actor_id, provider_code)
            VALUES (:messageId, :occurredAt, :status, :reason, :details, :actorType, :actorId, :providerCode)
            ON CONFLICT (message_id, occurred_at, status) DO NOTHING
            """;

    private static final String SELECT_STATUS_HISTORY = """
            SELECT message_id, occurred_at, status, reason, details, actor_type, actor_id, provider_code
            FROM message_status_history
            WHERE message_id IN (:messageIds)
            ORDER BY occurred_at, status
            """;

    private static final String UPSERT_ATTEMPT = """
            INSERT INTO delivery_attempt (id, message_id, request_at, provider_id, provider_code,
                                          provider_channel, provider_adapter_type, provider_message_id,
                                          attempt_no, result, response_code, response_at, latency_ms,
                                          error_class, error_description)
            VALUES (:id, :messageId, :requestAt, :providerId, :providerCode, :providerChannel,
                    :providerAdapterType, :providerMessageId, :attemptNo, :result, :responseCode,
                    :responseAt, :latencyMs, :errorClass, :errorDescription)
            ON CONFLICT (id, request_at) DO UPDATE SET
                provider_message_id = EXCLUDED.provider_message_id,
                result = EXCLUDED.result,
                response_code = EXCLUDED.response_code,
                response_at = EXCLUDED.response_at,
                latency_ms = EXCLUDED.latency_ms,
                error_class = EXCLUDED.error_class,
                error_description = EXCLUDED.error_description
            """;

    private static final String SELECT_ATTEMPTS = """
            SELECT id, message_id, request_at, provider_id, provider_code, provider_channel,
                   provider_adapter_type, provider_message_id, attempt_no, result, response_code,
                   response_at, latency_ms, error_class, error_description
            FROM delivery_attempt
            WHERE message_id IN (:messageIds)
            ORDER BY attempt_no
            """;

    private final JdbcClient jdbcClient;
    private final JsonCodec jsonCodec;
    private final ContentCodec contentCodec;
    private final MessageRowMapper messageRowMapper;
    private final StatusChangeRowMapper statusChangeRowMapper;
    private final DeliveryAttemptRowMapper attemptRowMapper;

    public MessagePersistenceAdapter(
            JdbcClient jdbcClient,
            JsonCodec jsonCodec,
            ContentCodec contentCodec,
            MessageRowMapper messageRowMapper,
            StatusChangeRowMapper statusChangeRowMapper,
            DeliveryAttemptRowMapper attemptRowMapper) {
        this.jdbcClient = jdbcClient;
        this.jsonCodec = jsonCodec;
        this.contentCodec = contentCodec;
        this.messageRowMapper = messageRowMapper;
        this.statusChangeRowMapper = statusChangeRowMapper;
        this.attemptRowMapper = attemptRowMapper;
    }

    @Override
    @Transactional
    public Message save(Message message) {
        writeMessageRow(message);
        message.statusHistory().forEach(change -> writeStatusChange(message.id(), change));
        message.attempts().forEach(this::writeAttempt);
        return message;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Message> findById(MessageId messageId) {
        return single(SELECT_MESSAGE + " WHERE id = :id", "id", messageId.value());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Message> findByExternalId(StreamId streamId, ExternalMessageId externalMessageId) {
        List<PartialMessage> rows = jdbcClient
                .sql(SELECT_MESSAGE + " WHERE external_id = :externalId AND stream_id = :streamId"
                        + " ORDER BY accepted_at DESC LIMIT 1")
                .param("externalId", externalMessageId.value())
                .param("streamId", streamId.value())
                .query(messageRowMapper.partialRowMapper())
                .list();
        return complete(rows).stream().findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Message> findByProviderMessageId(ProviderCode providerCode, ProviderMessageId providerMessageId) {
        List<PartialMessage> rows = jdbcClient
                .sql(SELECT_MESSAGE + """
                         WHERE id IN (SELECT message_id
                                      FROM delivery_attempt
                                      WHERE provider_code = :providerCode
                                        AND provider_message_id = :providerMessageId)
                         ORDER BY accepted_at DESC LIMIT 1
                        """)
                .param("providerCode", providerCode.value())
                .param("providerMessageId", providerMessageId.value())
                .query(messageRowMapper.partialRowMapper())
                .list();
        return complete(rows).stream().findFirst();
    }

    @Override
    @Transactional
    public List<MessageId> claimDispatchable(TrafficClass trafficClass, DispatchClaim claim) {
        Guard.notNull(trafficClass, "trafficClass");
        Guard.notNull(claim, "claim");
        return jdbcClient
                .sql(CLAIM_DISPATCHABLE)
                .param("trafficClass", trafficClass.name())
                .param("now", SqlValues.timestamp(claim.now()))
                .param("leaseUntil", SqlValues.timestamp(claim.leaseUntil()))
                .param("owner", claim.owner())
                .param("limit", claim.limit())
                .query((rs, rowNum) -> MessageId.of(rs.getObject("id", UUID.class)))
                .list();
    }

    @Override
    @Transactional
    public void releaseClaim(Message message, Instant nextAttemptAt) {
        Guard.notNull(message, "message");
        jdbcClient
                .sql(RELEASE_CLAIM)
                .param("id", message.id().value())
                .param("acceptedAt", SqlValues.timestamp(message.acceptedAt()))
                .param("nextAttemptAt", SqlValues.timestamp(nextAttemptAt))
                .update();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Message> findExpired(Instant now, int limit) {
        List<PartialMessage> rows = jdbcClient
                .sql(SELECT_MESSAGE
                        + " WHERE status IN (" + NON_TERMINAL_STATUSES + ")"
                        + "   AND " + NOT_DELIVERED_PUSH
                        + "   AND ((timing ->> 'sendBefore')::timestamptz <= :now"
                        + "        OR accepted_at + make_interval(secs => (timing ->> 'ttlSeconds')::bigint) <= :now)"
                        + " ORDER BY accepted_at"
                        + " LIMIT :limit")
                .param("now", SqlValues.timestamp(now))
                .param("limit", limit)
                .query(messageRowMapper.partialRowMapper())
                .list();
        return complete(rows);
    }

    /**
     * Messages still waiting for a delivery report from a provider (SG-03).
     *
     * <p>Anchored on the delivery attempt rather than on the message row: the message reached
     * {@code SENT_TO_PROVIDER} when the provider accepted it, and it is that acceptance whose age
     * decides when a report is overdue. The partial index of DB-05 on the non-terminal statuses is what
     * keeps this cheap on a partition holding a month of traffic.
     */
    @Override
    @Transactional(readOnly = true)
    public List<Message> findAwaitingDeliveryReport(ProviderCode providerCode, Instant acceptedBefore, int limit) {
        List<PartialMessage> rows = jdbcClient
                .sql(SELECT_MESSAGE + """
                         WHERE status = 'SENT_TO_PROVIDER'
                           AND selected_provider_code = :providerCode
                           AND id IN (SELECT message_id
                                      FROM delivery_attempt
                                      WHERE provider_code = :providerCode
                                        AND result = 'ACCEPTED'
                                        AND response_at <= :acceptedBefore)
                         ORDER BY accepted_at
                         LIMIT :limit
                        """)
                .param("providerCode", providerCode.value())
                .param("acceptedBefore", SqlValues.timestamp(acceptedBefore))
                .param("limit", limit)
                .query(messageRowMapper.partialRowMapper())
                .list();
        return complete(rows);
    }

    @Override
    @Transactional(readOnly = true)
    public long countTerminalByBatch(BatchId batchId) {
        return jdbcClient
                .sql("SELECT count(*) FROM message WHERE batch_id = :batchId"
                        + " AND (status NOT IN (" + NON_TERMINAL_STATUSES + ")"
                        + "      OR NOT " + NOT_DELIVERED_PUSH + ")")
                .param("batchId", batchId.value())
                .query(Long.class)
                .single();
    }

    private Optional<Message> single(String sql, String parameter, Object value) {
        List<PartialMessage> rows = jdbcClient
                .sql(sql)
                .param(parameter, value)
                .query(messageRowMapper.partialRowMapper())
                .list();
        return complete(rows).stream().findFirst();
    }

    /** Attaches the history and the attempts of a whole page of rows and builds the aggregates. */
    private List<Message> complete(List<PartialMessage> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = rows.stream().map(row -> row.messageId().value()).toList();
        Map<MessageId, List<StatusChange>> history = historyOf(ids);
        Map<MessageId, List<DeliveryAttempt>> attempts = attemptsOf(ids);
        return rows.stream()
                .map(row -> row.complete(
                        history.getOrDefault(row.messageId(), List.of()),
                        attempts.getOrDefault(row.messageId(), List.of())))
                .toList();
    }

    private Map<MessageId, List<StatusChange>> historyOf(List<UUID> messageIds) {
        Map<MessageId, List<StatusChange>> byMessage = new LinkedHashMap<>();
        jdbcClient
                .sql(SELECT_STATUS_HISTORY)
                .param("messageIds", messageIds)
                .query(statusChangeRowMapper)
                .list()
                .forEach(owned -> byMessage
                        .computeIfAbsent(owned.messageId(), key -> new ArrayList<>())
                        .add(owned.change()));
        return byMessage;
    }

    private Map<MessageId, List<DeliveryAttempt>> attemptsOf(List<UUID> messageIds) {
        Map<MessageId, List<DeliveryAttempt>> byMessage = new LinkedHashMap<>();
        jdbcClient
                .sql(SELECT_ATTEMPTS)
                .param("messageIds", messageIds)
                .query(attemptRowMapper)
                .list()
                .forEach(owned -> byMessage
                        .computeIfAbsent(owned.messageId(), key -> new ArrayList<>())
                        .add(owned.attempt()));
        return byMessage;
    }

    private void writeMessageRow(Message message) {
        TemplateRef template = message.template().orElse(null);
        ProviderRef provider = message.selectedProvider().orElse(null);
        jdbcClient
                .sql(UPSERT_MESSAGE)
                .param("id", message.id().value())
                .param("acceptedAt", SqlValues.timestamp(message.acceptedAt()))
                .param("externalId", message.envelope().externalId().value())
                .param("streamId", message.envelope().streamId().value())
                .param(
                        "batchId",
                        message.envelope().batchIdOptional().map(BatchId::value).orElse(null))
                .param("trafficClass", message.envelope().trafficClass().name())
                .param("priority", message.envelope().priority().name())
                .param("dedupKey", message.envelope().dedupKey().value())
                .param("correlationId", message.envelope().correlationId().value())
                .param("recipient", jsonCodec.write(RecipientJson.of(message.recipient())))
                .param("channelPlan", jsonCodec.write(ChannelPlanJson.of(message.channelPlan())))
                .param("contents", contentCodec.write(MessageContentsJson.of(message.contents())))
                .param("templateCode", template == null ? null : template.code().value())
                .param("templateLocale", template == null ? null : SqlValues.nameOf(template.locale()))
                .param(
                        "templateVersionId",
                        message.templateVersionId()
                                .map(TemplateVersionId::value)
                                .orElse(null))
                .param("templateVariables", template == null ? null : contentCodec.write(template.variables()))
                .param("timing", jsonCodec.write(TimingJson.of(message.timing())))
                .param("status", message.status().name())
                .param("statusReason", SqlValues.nameOf(message.statusReason().orElse(null)))
                .param(
                        "selectedChannel",
                        SqlValues.nameOf(message.selectedChannel().orElse(null)))
                .param(
                        "selectedProviderId",
                        provider == null ? null : provider.id().value())
                .param(
                        "selectedProviderCode",
                        provider == null ? null : provider.code().value())
                .param(
                        "selectedProviderAdapterType",
                        provider == null ? null : provider.adapterType().value())
                .param("segments", message.segments())
                .param("cost", SqlValues.amountOf(message.cost().orElse(null)))
                .param("costCurrency", SqlValues.currencyOf(message.cost().orElse(null)))
                .param(
                        "duplicateOf",
                        message.duplicateOf().map(MessageId::value).orElse(null))
                .param("test", message.isTest())
                .param("terminalAt", SqlValues.timestamp(message.terminalAt().orElse(null)))
                .update();
    }

    private void writeStatusChange(MessageId messageId, StatusChange change) {
        jdbcClient
                .sql(INSERT_STATUS_CHANGE)
                .param("messageId", messageId.value())
                .param("occurredAt", SqlValues.timestamp(change.occurredAt()))
                .param("status", change.status().name())
                .param("reason", SqlValues.nameOf(change.reason()))
                .param("details", change.details())
                .param("actorType", change.actor().type().name())
                .param("actorId", change.actor().id())
                .param(
                        "providerCode",
                        change.providerCodeOptional().map(ProviderCode::value).orElse(null))
                .update();
    }

    private void writeAttempt(DeliveryAttempt attempt) {
        jdbcClient
                .sql(UPSERT_ATTEMPT)
                .param("id", attempt.id().value())
                .param("messageId", attempt.messageId().value())
                .param("requestAt", SqlValues.timestamp(attempt.requestAt()))
                .param("providerId", attempt.provider().id().value())
                .param("providerCode", attempt.provider().code().value())
                .param("providerChannel", attempt.provider().channel().name())
                .param("providerAdapterType", attempt.provider().adapterType().value())
                .param(
                        "providerMessageId",
                        attempt.providerMessageId()
                                .map(ProviderMessageId::value)
                                .orElse(null))
                .param("attemptNo", attempt.attemptNumber())
                .param("result", attempt.result().name())
                .param("responseCode", attempt.responseCode().orElse(null))
                .param("responseAt", SqlValues.timestamp(attempt.responseAt().orElse(null)))
                .param(
                        "latencyMs",
                        attempt.latency()
                                .map(duration -> (int) duration.toMillis())
                                .orElse(null))
                .param("errorClass", SqlValues.nameOf(attempt.errorClass().orElse(null)))
                .param("errorDescription", attempt.errorDescription().orElse(null))
                .update();
    }
}
