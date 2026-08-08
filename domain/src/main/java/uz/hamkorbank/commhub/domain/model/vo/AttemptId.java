package uz.hamkorbank.commhub.domain.model.vo;

import java.util.UUID;
import uz.hamkorbank.commhub.domain.support.Guard;
import uz.hamkorbank.commhub.domain.support.UuidV7;

/** Identifier of a delivery attempt — UUIDv7 (§10.1 {@code delivery_attempt}). */
public record AttemptId(UUID value) {

    public AttemptId {
        Guard.notNull(value, "AttemptId.value");
    }

    public static AttemptId newId() {
        return new AttemptId(UuidV7.generate());
    }

    public static AttemptId of(UUID value) {
        return new AttemptId(value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
