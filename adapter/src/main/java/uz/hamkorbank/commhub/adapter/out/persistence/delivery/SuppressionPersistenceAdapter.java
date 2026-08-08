package uz.hamkorbank.commhub.adapter.out.persistence.delivery;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
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
 * <p>{@code findActive…} lookups match a row with no channel as well as one for the requested channel: a
 * client who opted out of everything must not be reachable through a single channel that happens to have no
 * row of its own. Expiry is applied in SQL so a stale entry never even reaches the filter.
 *
 * <p>The administration lookups are the other shape — one exact channel scope, expiry included — because
 * they exist to find the row the unique indexes of §10.1 would collide with. {@code IS NOT DISTINCT FROM}
 * rather than {@code =} for the channel: the "all channels" scope is stored as {@code NULL}, and comparing
 * it with {@code =} would never match it.
 */
@Repository
public class SuppressionPersistenceAdapter implements SuppressionRepository {

    private static final String SELECT = """
            SELECT id, channel, address_hash, client_id, reason, valid_until, created_by, created_at
            FROM suppression_list
            """;

    private static final String ACTIVE_FOR_CHANNEL =
            " AND (channel IS NULL OR channel = :channel) AND (valid_until IS NULL OR valid_until > :now)";

    // CAST: «все каналы» — это NULL, и драйвер должен знать тип параметра, чтобы сравнить его с колонкой.
    private static final String EXACT_CHANNEL = " AND channel IS NOT DISTINCT FROM CAST(:channel AS text)";

    private static final String INSERT_COLUMNS = """
            INSERT INTO suppression_list (id, channel, address_hash, client_id, reason, valid_until,
                                          created_by, created_at)
            VALUES (:id, :channel, :addressHash, :clientId, :reason, :validUntil, :createdBy, :createdAt)
            """;

    private static final String UPSERT = INSERT_COLUMNS + """
            ON CONFLICT (id) DO UPDATE SET
                reason = EXCLUDED.reason,
                valid_until = EXCLUDED.valid_until
            """;

    /**
     * No conflict target on purpose: the table has one unique index for addresses and another for clients
     * (V5), and a single {@code ON CONFLICT} cannot name both. "Whatever collides, leave what is there" is
     * exactly the contract of {@link #saveIfAbsent}.
     */
    private static final String INSERT_IF_ABSENT = INSERT_COLUMNS + " ON CONFLICT DO NOTHING";

    private final JdbcClient jdbcClient;

    public SuppressionPersistenceAdapter(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    @Transactional
    public SuppressionEntry save(SuppressionEntry entry) {
        bind(jdbcClient.sql(UPSERT), entry).update();
        return entry;
    }

    @Override
    @Transactional
    public SuppressionEntry saveIfAbsent(SuppressionEntry entry) {
        if (bind(jdbcClient.sql(INSERT_IF_ABSENT), entry).update() > 0) {
            return entry;
        }
        Channel channel = entry.channel().orElse(null);
        return entry.addressHash()
                .flatMap(hash -> findByAddress(hash, channel))
                .or(() -> entry.clientId().flatMap(clientId -> findByClient(clientId, channel)))
                .orElse(entry);
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
    public Optional<SuppressionEntry> findById(SuppressionEntryId entryId) {
        return jdbcClient
                .sql(SELECT + " WHERE id = :id")
                .param("id", entryId.value())
                .query(rowMapper())
                .optional();
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

    @Override
    @Transactional(readOnly = true)
    public Optional<SuppressionEntry> findByAddress(AddressHash addressHash, Channel channel) {
        return jdbcClient
                .sql(SELECT + " WHERE address_hash = :addressHash" + EXACT_CHANNEL + " LIMIT 1")
                .param("addressHash", addressHash.value())
                .param("channel", SqlValues.nameOf(channel))
                .query(rowMapper())
                .optional();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SuppressionEntry> findByClient(ClientId clientId, Channel channel) {
        return jdbcClient
                .sql(SELECT + " WHERE client_id = :clientId" + EXACT_CHANNEL + " LIMIT 1")
                .param("clientId", clientId.value())
                .param("channel", SqlValues.nameOf(channel))
                .query(rowMapper())
                .optional();
    }

    @Override
    @Transactional(readOnly = true)
    public List<SuppressionEntry> findAll(
            Channel channel, SuppressionReason reason, ClientId clientId, int limit, int offset) {
        // SQL собирается по заданным фильтрам, как в каталоге шаблонов: незаданный фильтр не должен
        // становиться связанным NULL — иначе планировщик теряет индекс, а драйвер — тип параметра.
        StringBuilder sql = new StringBuilder(SELECT).append(" WHERE 1 = 1");
        if (channel != null) {
            sql.append(" AND channel = :channel");
        }
        if (reason != null) {
            sql.append(" AND reason = :reason");
        }
        if (clientId != null) {
            sql.append(" AND client_id = :clientId");
        }
        sql.append(" ORDER BY created_at DESC, id LIMIT :limit OFFSET :offset");
        JdbcClient.StatementSpec statement =
                jdbcClient.sql(sql.toString()).param("limit", limit).param("offset", offset);
        if (channel != null) {
            statement = statement.param("channel", channel.name());
        }
        if (reason != null) {
            statement = statement.param("reason", reason.name());
        }
        if (clientId != null) {
            statement = statement.param("clientId", clientId.value());
        }
        return statement.query(rowMapper()).list();
    }

    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec statement, SuppressionEntry entry) {
        return statement
                .param("id", entry.id().value())
                .param("channel", SqlValues.nameOf(entry.channel().orElse(null)))
                .param(
                        "addressHash",
                        entry.addressHash().map(AddressHash::value).orElse(null))
                .param("clientId", entry.clientId().map(ClientId::value).orElse(null))
                .param("reason", entry.reason().name())
                .param("validUntil", SqlValues.timestamp(entry.validUntil().orElse(null)))
                .param("createdBy", entry.createdBy().orElse(null))
                .param("createdAt", SqlValues.timestamp(entry.createdAt()));
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
