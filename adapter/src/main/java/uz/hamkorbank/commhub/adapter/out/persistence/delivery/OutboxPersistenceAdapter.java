package uz.hamkorbank.commhub.adapter.out.persistence.delivery;

import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import uz.hamkorbank.commhub.adapter.out.persistence.json.MessageStatusEventJson;
import uz.hamkorbank.commhub.adapter.out.persistence.support.JsonCodec;
import uz.hamkorbank.commhub.adapter.out.persistence.support.SqlValues;
import uz.hamkorbank.commhub.application.port.out.OutboxEvent;
import uz.hamkorbank.commhub.application.port.out.OutboxEventType;
import uz.hamkorbank.commhub.application.port.out.OutboxPort;
import uz.hamkorbank.commhub.application.port.out.PendingOutboxEvent;

/**
 * {@link OutboxPort} over the partitioned {@code outbox_event} table (AD-03).
 *
 * <p>{@link Propagation#MANDATORY}: an outbox row only means anything inside the transaction that made
 * the business change. Appending it in a transaction of its own would reintroduce exactly the dual-write
 * the pattern exists to remove, so a caller without a transaction fails loudly instead of silently
 * losing the guarantee. The relay's side is mandatory for the same kind of reason — its claim on a row
 * lasts exactly as long as its transaction.
 *
 * <p>The insert is {@code DO NOTHING} on conflict: an at-least-once redelivery replays the same event id
 * and must not queue a second publication.
 *
 * <p>The claim is {@code FOR UPDATE SKIP LOCKED} over the partial index of unpublished rows: relays on
 * several instances each take a disjoint batch instead of blocking on one another, and a batch that is
 * never marked — because its instance died — is unlocked by the abort and picked up by the next pass.
 */
@Repository
public class OutboxPersistenceAdapter implements OutboxPort {

    private static final String INSERT = """
            INSERT INTO outbox_event (id, created_at, aggregate_type, aggregate_id, event_type, payload)
            VALUES (:id, :createdAt, :aggregateType, :aggregateId, :eventType, CAST(:payload AS jsonb))
            ON CONFLICT (id, created_at) DO NOTHING
            """;

    private static final String CLAIM_UNPUBLISHED = """
            SELECT id, created_at, aggregate_type, aggregate_id, event_type, payload, attempts
            FROM outbox_event
            WHERE published_at IS NULL
            ORDER BY created_at, id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """;

    private static final String MARK_PUBLISHED = """
            UPDATE outbox_event
            SET published_at = :publishedAt
            WHERE id = :id AND created_at = :createdAt AND published_at IS NULL
            """;

    private static final String MARK_FAILED = """
            UPDATE outbox_event
            SET attempts = attempts + 1, last_error = :error
            WHERE id = :id AND created_at = :createdAt AND published_at IS NULL
            """;

    /** {@code outbox_event.last_error} is {@code varchar(1024)}; a stack-trace-long message is cut. */
    private static final int MAX_ERROR_LENGTH = 1024;

    private final JdbcClient jdbcClient;
    private final JsonCodec jsonCodec;

    public OutboxPersistenceAdapter(JdbcClient jdbcClient, JsonCodec jsonCodec) {
        this.jdbcClient = jdbcClient;
        this.jsonCodec = jsonCodec;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(OutboxEvent event) {
        jdbcClient
                .sql(INSERT)
                .param("id", event.eventId())
                .param("createdAt", SqlValues.timestamp(event.occurredAt()))
                .param("aggregateType", event.aggregateType())
                .param("aggregateId", event.aggregateId())
                .param("eventType", event.type().name())
                .param("payload", jsonCodec.write(MessageStatusEventJson.of(event.payload())))
                .update();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void appendAll(List<OutboxEvent> events) {
        events.forEach(this::append);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<PendingOutboxEvent> pollUnpublished(int limit) {
        return jdbcClient
                .sql(CLAIM_UNPUBLISHED)
                .param("limit", limit)
                .query((rs, rowNum) -> new PendingOutboxEvent(
                        SqlValues.uuid(rs, "id"),
                        SqlValues.instant(rs, "created_at"),
                        SqlValues.enumValue(rs, "event_type", OutboxEventType.class),
                        rs.getString("aggregate_type"),
                        rs.getString("aggregate_id"),
                        jsonCodec
                                .read(rs.getString("payload"), MessageStatusEventJson.class)
                                .toDomain(),
                        rs.getInt("attempts")))
                .list();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void markPublished(PendingOutboxEvent event, Instant publishedAt) {
        jdbcClient
                .sql(MARK_PUBLISHED)
                .param("id", event.eventId())
                .param("createdAt", SqlValues.timestamp(event.createdAt()))
                .param("publishedAt", SqlValues.timestamp(publishedAt))
                .update();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void markFailed(PendingOutboxEvent event, String error) {
        jdbcClient
                .sql(MARK_FAILED)
                .param("id", event.eventId())
                .param("createdAt", SqlValues.timestamp(event.createdAt()))
                .param("error", truncate(error))
                .update();
    }

    private static String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
    }
}
