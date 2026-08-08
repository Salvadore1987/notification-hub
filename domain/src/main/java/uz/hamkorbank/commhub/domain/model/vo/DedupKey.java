package uz.hamkorbank.commhub.domain.model.vo;

import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Idempotency key of a submission (FR-1.5).
 *
 * <p>Either supplied by the source system or derived from {@code (streamId, externalMessageId)}. A
 * repeated submission with the same key inside the dedup window (24 h by default) yields
 * {@code DUPLICATE} without a second send.
 */
public record DedupKey(String value) {

    public static final int MAX_LENGTH = 128;

    private static final String SEPARATOR = ":";

    public DedupKey {
        Guard.notBlank(value, "DedupKey.value");
        Guard.maxLength(value, MAX_LENGTH, "DedupKey.value");
    }

    public static DedupKey of(String value) {
        return new DedupKey(value);
    }

    /** Default key: {@code <streamId>:<externalMessageId>} (FR-1.5). */
    public static DedupKey forExternalId(StreamId streamId, ExternalMessageId externalMessageId) {
        Guard.notNull(streamId, "streamId");
        Guard.notNull(externalMessageId, "externalMessageId");
        return new DedupKey(streamId.value() + SEPARATOR + externalMessageId.value());
    }

    @Override
    public String toString() {
        return value;
    }
}
