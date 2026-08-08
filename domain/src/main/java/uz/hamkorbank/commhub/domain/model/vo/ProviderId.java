package uz.hamkorbank.commhub.domain.model.vo;

import java.util.UUID;
import uz.hamkorbank.commhub.domain.support.Guard;
import uz.hamkorbank.commhub.domain.support.UuidV7;

/** Identifier of a provider integration profile — UUIDv7 (§6.1, FR-2.1). */
public record ProviderId(UUID value) {

    public ProviderId {
        Guard.notNull(value, "ProviderId.value");
    }

    public static ProviderId newId() {
        return new ProviderId(UuidV7.generate());
    }

    public static ProviderId of(UUID value) {
        return new ProviderId(value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
