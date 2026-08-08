package uz.hamkorbank.commhub.domain.model.vo;

import java.util.UUID;
import uz.hamkorbank.commhub.domain.support.Guard;
import uz.hamkorbank.commhub.domain.support.UuidV7;

/** Identifier of a template — UUIDv7 (§6.1, FR-4.1). */
public record TemplateId(UUID value) {

    public TemplateId {
        Guard.notNull(value, "TemplateId.value");
    }

    public static TemplateId newId() {
        return new TemplateId(UuidV7.generate());
    }

    public static TemplateId of(UUID value) {
        return new TemplateId(value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
