package uz.hamkorbank.commhub.domain.model.vo;

import java.util.UUID;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * End-to-end trace identifier carried through the whole pipeline (FR-8.6, OBS-02).
 *
 * <p>Free-form string so that an external trace id (W3C {@code traceparent}) can be reused.
 */
public record CorrelationId(String value) {

    public static final int MAX_LENGTH = 64;

    public CorrelationId {
        Guard.notBlank(value, "CorrelationId.value");
        Guard.maxLength(value, MAX_LENGTH, "CorrelationId.value");
    }

    public static CorrelationId of(String value) {
        return new CorrelationId(value);
    }

    /** Generates a correlation id for a submission that arrives without one. */
    public static CorrelationId newId() {
        return new CorrelationId(UUID.randomUUID().toString());
    }

    @Override
    public String toString() {
        return value;
    }
}
