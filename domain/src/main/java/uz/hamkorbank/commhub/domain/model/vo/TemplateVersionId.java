package uz.hamkorbank.commhub.domain.model.vo;

import java.util.UUID;
import uz.hamkorbank.commhub.domain.support.Guard;
import uz.hamkorbank.commhub.domain.support.UuidV7;

/** Identifier of a concrete template version — UUIDv7 (§10.1 {@code template_version}). */
public record TemplateVersionId(UUID value) {

    public TemplateVersionId {
        Guard.notNull(value, "TemplateVersionId.value");
    }

    public static TemplateVersionId newId() {
        return new TemplateVersionId(UuidV7.generate());
    }

    public static TemplateVersionId of(UUID value) {
        return new TemplateVersionId(value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
