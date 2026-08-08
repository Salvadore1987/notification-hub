package uz.hamkorbank.commhub.domain.model.vo;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import uz.hamkorbank.commhub.domain.support.Guard;
import uz.hamkorbank.commhub.domain.support.UuidV7;

/**
 * Internal identifier of a message — UUIDv7 (SRS §5.2, §10.1).
 *
 * <p>Time-ordered by construction, which keeps inserts into the partitioned {@code message} table
 * append-only (DB-02).
 */
public record MessageId(UUID value) {

    public MessageId {
        Guard.notNull(value, "MessageId.value");
    }

    /** Generates a new time-ordered identifier. */
    public static MessageId newId() {
        return new MessageId(UuidV7.generate());
    }

    public static MessageId of(UUID value) {
        return new MessageId(value);
    }

    public static MessageId fromString(String value) {
        Guard.notBlank(value, "MessageId.value");
        return new MessageId(UUID.fromString(value));
    }

    /** Creation instant embedded in the identifier, if it is a UUIDv7. */
    public Optional<Instant> createdAt() {
        return UuidV7.isUuidV7(value) ? Optional.of(UuidV7.timestampOf(value)) : Optional.empty();
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
