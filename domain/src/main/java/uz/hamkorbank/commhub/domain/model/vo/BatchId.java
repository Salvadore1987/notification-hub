package uz.hamkorbank.commhub.domain.model.vo;

import java.util.UUID;
import uz.hamkorbank.commhub.domain.support.Guard;
import uz.hamkorbank.commhub.domain.support.UuidV7;

/** Identifier of a batch send — UUIDv7 (§6.1, FR-1.6). */
public record BatchId(UUID value) {

    public BatchId {
        Guard.notNull(value, "BatchId.value");
    }

    public static BatchId newId() {
        return new BatchId(UuidV7.generate());
    }

    public static BatchId of(UUID value) {
        return new BatchId(value);
    }

    public static BatchId fromString(String value) {
        Guard.notBlank(value, "BatchId.value");
        return new BatchId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
