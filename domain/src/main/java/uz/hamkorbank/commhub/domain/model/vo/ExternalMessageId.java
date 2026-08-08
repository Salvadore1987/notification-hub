package uz.hamkorbank.commhub.domain.model.vo;

import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Message identifier assigned by the source system; unique inside its stream (SRS §5.2, FR-1.5).
 *
 * <p>Together with {@link StreamId} it forms the natural idempotency key of a submission.
 */
public record ExternalMessageId(String value) {

    public static final int MAX_LENGTH = 64;

    public ExternalMessageId {
        Guard.notBlank(value, "ExternalMessageId.value");
        Guard.maxLength(value, MAX_LENGTH, "ExternalMessageId.value");
    }

    public static ExternalMessageId of(String value) {
        return new ExternalMessageId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
