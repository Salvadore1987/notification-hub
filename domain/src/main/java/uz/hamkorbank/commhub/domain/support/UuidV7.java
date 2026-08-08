package uz.hamkorbank.commhub.domain.support;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;

/**
 * UUID version 7 generator (RFC 9562): 48-bit Unix millisecond timestamp + 12-bit sequence + 62
 * random bits.
 *
 * <p>Time-ordered identifiers are a project rule for every primary key: they keep B-tree inserts
 * append-only, which matters for the time-partitioned {@code message} tables (DB-02, DB-05).
 *
 * <p>Only the JDK is used, so the domain stays framework-free (AR-02).
 */
public final class UuidV7 {

    private static final int VERSION = 7;
    private static final int MAX_SEQUENCE = 0xFFF;
    private static final long TIMESTAMP_MASK = 0xFFFF_FFFF_FFFFL;
    private static final long VARIANT_BITS = 0x8000_0000_0000_0000L;
    private static final long RANDOM_B_MASK = 0x3FFF_FFFF_FFFF_FFFFL;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Object LOCK = new Object();

    private static long lastEpochMillis = -1L;
    private static int sequence;

    private UuidV7() {}

    /** Generates a UUIDv7 stamped with the current wall clock. */
    public static UUID generate() {
        return generate(System.currentTimeMillis());
    }

    /**
     * Generates a UUIDv7 stamped with the given epoch millisecond.
     *
     * <p>Identifiers produced for the same millisecond are monotonic: the 12-bit sequence is
     * incremented instead of being re-randomised.
     */
    public static UUID generate(long epochMillis) {
        Guard.notNegative(epochMillis, "epochMillis");
        Guard.isTrue(epochMillis <= TIMESTAMP_MASK, "epochMillis does not fit into the 48-bit UUIDv7 timestamp");

        int currentSequence;
        synchronized (LOCK) {
            if (epochMillis == lastEpochMillis) {
                sequence = (sequence + 1) & MAX_SEQUENCE;
            } else {
                lastEpochMillis = epochMillis;
                sequence = RANDOM.nextInt(MAX_SEQUENCE + 1);
            }
            currentSequence = sequence;
        }

        long mostSignificantBits =
                ((epochMillis & TIMESTAMP_MASK) << 16) | ((long) VERSION << 12) | (long) currentSequence;
        long leastSignificantBits = (RANDOM.nextLong() & RANDOM_B_MASK) | VARIANT_BITS;
        return new UUID(mostSignificantBits, leastSignificantBits);
    }

    /** Extracts the embedded creation instant of a UUIDv7. */
    public static Instant timestampOf(UUID uuid) {
        Guard.notNull(uuid, "uuid");
        Guard.isTrue(uuid.version() == VERSION, "not a UUIDv7: version=" + uuid.version());
        return Instant.ofEpochMilli(uuid.getMostSignificantBits() >>> 16 & TIMESTAMP_MASK);
    }

    /** Whether the given identifier is a well-formed UUIDv7 (version 7, variant 2). */
    public static boolean isUuidV7(UUID uuid) {
        return uuid != null && uuid.version() == VERSION && uuid.variant() == 2;
    }
}
