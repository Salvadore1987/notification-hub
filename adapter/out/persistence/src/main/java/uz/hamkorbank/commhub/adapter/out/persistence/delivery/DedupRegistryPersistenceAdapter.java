package uz.hamkorbank.commhub.adapter.out.persistence.delivery;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import uz.hamkorbank.commhub.adapter.out.persistence.support.SqlValues;
import uz.hamkorbank.commhub.application.port.out.DedupRegistryPort;
import uz.hamkorbank.commhub.domain.model.vo.DedupKey;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;

/**
 * {@link DedupRegistryPort} over {@code dedup_registry} (FR-1.5).
 *
 * <p>Registration is one statement: the insert either wins or loses the race, and the losing side gets
 * the winner's message id back through {@code RETURNING}. Reading first and inserting after would leave
 * a window in which two concurrent submissions of the same key both look new — the very duplicate the
 * registry exists to prevent.
 *
 * <p>An expired row is treated as absent and taken over by the newcomer: the window has passed, so the
 * resubmission is a new message rather than a duplicate.
 */
@Repository
public class DedupRegistryPersistenceAdapter implements DedupRegistryPort {

    private static final String REGISTER = """
            INSERT INTO dedup_registry (dedup_key, message_id, registered_at, expires_at)
            VALUES (:dedupKey, :messageId, :registeredAt, :expiresAt)
            ON CONFLICT (dedup_key) DO UPDATE SET
                message_id = EXCLUDED.message_id,
                registered_at = EXCLUDED.registered_at,
                expires_at = EXCLUDED.expires_at
            WHERE dedup_registry.expires_at <= :registeredAt
            RETURNING message_id
            """;

    private static final String FIND_ORIGINAL = """
            SELECT message_id
            FROM dedup_registry
            WHERE dedup_key = :dedupKey
              AND registered_at >= :windowStart
              AND expires_at > :windowStart
            """;

    private static final String SELECT_EXISTING = "SELECT message_id FROM dedup_registry WHERE dedup_key = :dedupKey";

    private static final String PURGE = """
            DELETE FROM dedup_registry
            WHERE dedup_key IN (SELECT dedup_key FROM dedup_registry WHERE expires_at <= :now LIMIT :limit)
            """;

    private final JdbcClient jdbcClient;

    public DedupRegistryPersistenceAdapter(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    @Transactional
    public Optional<MessageId> register(DedupKey key, MessageId messageId, Instant registeredAt, Instant expiresAt) {
        Optional<UUID> registered = jdbcClient
                .sql(REGISTER)
                .param("dedupKey", key.value())
                .param("messageId", messageId.value())
                .param("registeredAt", SqlValues.timestamp(registeredAt))
                .param("expiresAt", SqlValues.timestamp(expiresAt))
                .query(UUID.class)
                .optional();
        if (registered.isPresent()) {
            // The key is ours: either it was free or the previous window had expired.
            return Optional.empty();
        }
        // The conflicting row is still inside its window — the submission is a duplicate of it.
        return jdbcClient
                .sql(SELECT_EXISTING)
                .param("dedupKey", key.value())
                .query(UUID.class)
                .optional()
                .map(MessageId::of);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<MessageId> findOriginal(DedupKey key, Instant windowStart) {
        return jdbcClient
                .sql(FIND_ORIGINAL)
                .param("dedupKey", key.value())
                .param("windowStart", SqlValues.timestamp(windowStart))
                .query(UUID.class)
                .optional()
                .map(MessageId::of);
    }

    @Override
    @Transactional
    public long purgeExpired(Instant now, int limit) {
        return jdbcClient
                .sql(PURGE)
                .param("now", SqlValues.timestamp(now))
                .param("limit", limit)
                .update();
    }
}
