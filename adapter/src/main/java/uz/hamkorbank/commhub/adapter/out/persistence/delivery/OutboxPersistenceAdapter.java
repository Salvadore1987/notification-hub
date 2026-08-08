package uz.hamkorbank.commhub.adapter.out.persistence.delivery;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import uz.hamkorbank.commhub.adapter.out.persistence.json.MessageStatusEventJson;
import uz.hamkorbank.commhub.adapter.out.persistence.support.JsonCodec;
import uz.hamkorbank.commhub.adapter.out.persistence.support.SqlValues;
import uz.hamkorbank.commhub.application.port.out.OutboxEvent;
import uz.hamkorbank.commhub.application.port.out.OutboxPort;

/**
 * {@link OutboxPort} over the partitioned {@code outbox_event} table (AD-03).
 *
 * <p>{@link Propagation#MANDATORY}: an outbox row only means anything inside the transaction that made
 * the business change. Appending it in a transaction of its own would reintroduce exactly the dual-write
 * the pattern exists to remove, so a caller without a transaction fails loudly instead of silently
 * losing the guarantee.
 *
 * <p>The insert is {@code DO NOTHING} on conflict: an at-least-once redelivery replays the same event id
 * and must not queue a second publication.
 */
@Repository
public class OutboxPersistenceAdapter implements OutboxPort {

    private static final String INSERT = """
            INSERT INTO outbox_event (id, created_at, aggregate_type, aggregate_id, event_type, payload)
            VALUES (:id, :createdAt, :aggregateType, :aggregateId, :eventType, CAST(:payload AS jsonb))
            ON CONFLICT (id, created_at) DO NOTHING
            """;

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
}
