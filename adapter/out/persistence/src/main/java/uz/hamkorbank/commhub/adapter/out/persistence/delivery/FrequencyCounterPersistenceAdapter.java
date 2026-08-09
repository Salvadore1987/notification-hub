package uz.hamkorbank.commhub.adapter.out.persistence.delivery;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import uz.hamkorbank.commhub.adapter.out.persistence.support.SqlValues;
import uz.hamkorbank.commhub.application.port.out.FrequencyCounterPort;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.vo.AddressHash;

/**
 * {@link FrequencyCounterPort} over {@code frequency_counter} (FR-5.4).
 *
 * <p>One row per address, channel and hour rather than per message: the cap is a rate, and at the
 * forecast volumes (§12.2) a row per send would be a second table the size of {@code message} kept for
 * the sake of one number. The bucket the window's left edge falls into is counted whole, so the answer
 * may be slightly high — the direction in which a cap that only alerts (FR-5.4, MVP) does no harm.
 *
 * <p>Counting and registering are one statement each and both idempotent-friendly: the increment is an
 * upsert, so two dispatchers sending to the same recipient in the same hour cannot lose a count.
 */
@Repository
public class FrequencyCounterPersistenceAdapter implements FrequencyCounterPort {

    private static final String COUNT_SINCE = """
            SELECT COALESCE(SUM(counter), 0)
            FROM frequency_counter
            WHERE address_hash = :addressHash
              AND channel = :channel
              AND bucket_start >= :bucketStart
            """;

    private static final String REGISTER = """
            INSERT INTO frequency_counter (address_hash, channel, bucket_start, counter)
            VALUES (:addressHash, :channel, :bucketStart, 1)
            ON CONFLICT (address_hash, channel, bucket_start) DO UPDATE SET
                counter = frequency_counter.counter + 1
            """;

    private static final String PURGE = """
            DELETE FROM frequency_counter
            WHERE (address_hash, channel, bucket_start) IN (
                SELECT address_hash, channel, bucket_start
                FROM frequency_counter
                WHERE bucket_start < :cutoff
                LIMIT :limit)
            """;

    private final JdbcClient jdbcClient;

    public FrequencyCounterPersistenceAdapter(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    @Transactional(readOnly = true)
    public long countSince(AddressHash addressHash, Channel channel, Instant since) {
        return jdbcClient
                .sql(COUNT_SINCE)
                .param("addressHash", addressHash.value())
                .param("channel", channel.name())
                .param("bucketStart", SqlValues.timestamp(bucketOf(since)))
                .query(Long.class)
                .single();
    }

    @Override
    @Transactional
    public void register(AddressHash addressHash, Channel channel, Instant occurredAt) {
        jdbcClient
                .sql(REGISTER)
                .param("addressHash", addressHash.value())
                .param("channel", channel.name())
                .param("bucketStart", SqlValues.timestamp(bucketOf(occurredAt)))
                .update();
    }

    @Override
    @Transactional
    public long purgeBefore(Instant cutoff, int limit) {
        return jdbcClient
                .sql(PURGE)
                .param("cutoff", SqlValues.timestamp(bucketOf(cutoff)))
                .param("limit", limit)
                .update();
    }

    /** Hour the instant belongs to; the bucket key of the table (UTC, like every stored instant). */
    private static Instant bucketOf(Instant instant) {
        return instant.truncatedTo(ChronoUnit.HOURS);
    }
}
