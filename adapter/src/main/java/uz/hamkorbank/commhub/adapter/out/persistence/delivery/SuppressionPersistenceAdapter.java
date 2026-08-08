package uz.hamkorbank.commhub.adapter.out.persistence.delivery;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import uz.hamkorbank.commhub.adapter.out.persistence.support.SqlValues;
import uz.hamkorbank.commhub.application.port.out.SuppressionRepository;
import uz.hamkorbank.commhub.domain.model.SuppressionEntry;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.SuppressionReason;
import uz.hamkorbank.commhub.domain.model.vo.AddressHash;
import uz.hamkorbank.commhub.domain.model.vo.ClientId;
import uz.hamkorbank.commhub.domain.model.vo.SuppressionEntryId;

/**
 * {@link SuppressionRepository} over {@code suppression_list} (FR-5.1).
 *
 * <p>Lookups match a row with no channel as well as one for the requested channel: a client who opted
 * out of everything must not be reachable through a single channel that happens to have no row of its
 * own. Expiry is applied in SQL so a stale entry never even reaches the filter.
 */
@Repository
public class SuppressionPersistenceAdapter implements SuppressionRepository {

    private static final String SELECT = """
            SELECT id, channel, address_hash, client_id, reason, valid_until, created_by, created_at
            FROM suppression_list
            """;

    private static final String ACTIVE_FOR_CHANNEL =
            " AND (channel IS NULL OR channel = :channel) AND (valid_until IS NULL OR valid_until > :now)";

    private static final String UPSERT = """
            INSERT INTO suppression_list (id, channel, address_hash, client_id, reason, valid_until,
                                          created_by, created_at)
            VALUES (:id, :channel, :addressHash, :clientId, :reason, :validUntil, :createdBy, :createdAt)
            ON CONFLICT (id) DO UPDATE SET
                reason = EXCLUDED.reason,
                valid_until = EXCLUDED.valid_until
            """;

    private final JdbcClient jdbcClient;

    public SuppressionPersistenceAdapter(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    @Transactional
    public SuppressionEntry save(SuppressionEntry entry) {
        jdbcClient
                .sql(UPSERT)
                .param("id", entry.id().value())
                .param("channel", SqlValues.nameOf(entry.channel().orElse(null)))
                .param(
                        "addressHash",
                        entry.addressHash().map(AddressHash::value).orElse(null))
                .param("clientId", entry.clientId().map(ClientId::value).orElse(null))
                .param("reason", entry.reason().name())
                .param("validUntil", SqlValues.timestamp(entry.validUntil().orElse(null)))
                .param("createdBy", entry.createdBy().orElse(null))
                .param("createdAt", SqlValues.timestamp(entry.createdAt()))
                .update();
        return entry;
    }

    @Override
    @Transactional
    public void delete(SuppressionEntryId entryId) {
        jdbcClient
                .sql("DELETE FROM suppression_list WHERE id = :id")
                .param("id", entryId.value())
                .update();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SuppressionEntry> findActiveByAddress(AddressHash addressHash, Channel channel, Instant now) {
        return jdbcClient
                .sql(SELECT + " WHERE address_hash = :addressHash" + ACTIVE_FOR_CHANNEL + " LIMIT 1")
                .param("addressHash", addressHash.value())
                .param("channel", SqlValues.nameOf(channel))
                .param("now", SqlValues.timestamp(now))
                .query(rowMapper())
                .optional();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SuppressionEntry> findActiveByClient(ClientId clientId, Channel channel, Instant now) {
        return jdbcClient
                .sql(SELECT + " WHERE client_id = :clientId" + ACTIVE_FOR_CHANNEL + " LIMIT 1")
                .param("clientId", clientId.value())
                .param("channel", SqlValues.nameOf(channel))
                .param("now", SqlValues.timestamp(now))
                .query(rowMapper())
                .optional();
    }

    private RowMapper<SuppressionEntry> rowMapper() {
        return (rs, rowNum) -> {
            SuppressionEntry entry = build(rs);
            Instant validUntil = SqlValues.instant(rs, "valid_until");
            if (validUntil != null) {
                entry.expireAt(validUntil);
            }
            return entry;
        };
    }

    private SuppressionEntry build(ResultSet rs) throws SQLException {
        SuppressionEntryId id = SuppressionEntryId.of(SqlValues.uuid(rs, "id"));
        Channel channel = SqlValues.enumValue(rs, "channel", Channel.class);
        SuppressionReason reason = SqlValues.enumValue(rs, "reason", SuppressionReason.class);
        Instant createdAt = SqlValues.instant(rs, "created_at");
        String createdBy = rs.getString("created_by");
        String addressHash = rs.getString("address_hash");
        if (addressHash != null) {
            return SuppressionEntry.forAddress(id, channel, new AddressHash(addressHash), reason, createdAt, createdBy);
        }
        return SuppressionEntry.forClient(
                id, channel, ClientId.of(rs.getString("client_id")), reason, createdAt, createdBy);
    }
}
