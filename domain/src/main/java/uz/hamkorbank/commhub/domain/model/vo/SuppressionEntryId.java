package uz.hamkorbank.commhub.domain.model.vo;

import java.util.UUID;
import uz.hamkorbank.commhub.domain.support.Guard;
import uz.hamkorbank.commhub.domain.support.UuidV7;

/** Identifier of a suppression-list entry — UUIDv7 (§10.1 {@code suppression_list}). */
public record SuppressionEntryId(UUID value) {

    public SuppressionEntryId {
        Guard.notNull(value, "SuppressionEntryId.value");
    }

    public static SuppressionEntryId newId() {
        return new SuppressionEntryId(UuidV7.generate());
    }

    public static SuppressionEntryId of(UUID value) {
        return new SuppressionEntryId(value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
