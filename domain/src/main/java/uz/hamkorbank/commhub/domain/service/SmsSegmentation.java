package uz.hamkorbank.commhub.domain.service;

import uz.hamkorbank.commhub.domain.model.type.SmsEncoding;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Result of an SMS segmentation calculation (MP-06, §18.3).
 *
 * @param encoding encoding the whole message will be sent in
 * @param characterCount billed character count — GSM-7 escape characters count as two
 * @param segments number of SMS parts; drives cost and quotas (FR-2.6, FR-6.2)
 */
public record SmsSegmentation(SmsEncoding encoding, int characterCount, int segments) {

    public SmsSegmentation {
        Guard.notNull(encoding, "SmsSegmentation.encoding");
        Guard.notNegative(characterCount, "SmsSegmentation.characterCount");
        Guard.positive(segments, "SmsSegmentation.segments");
    }

    /** Total capacity of the allocated segments. */
    public int capacity() {
        return segments == 1 ? encoding.singleSegmentCapacity() : segments * encoding.concatenatedSegmentCapacity();
    }

    /** How many more characters fit without allocating another segment. */
    public int remainingCharacters() {
        return capacity() - characterCount;
    }

    public boolean isMultipart() {
        return segments > 1;
    }
}
