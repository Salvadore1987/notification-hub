package uz.hamkorbank.commhub.domain.model.type;

/**
 * SMS encoding and its segment capacities (§18.3, MP-06).
 *
 * <p>A single non-GSM-7 character switches the whole message to UCS-2.
 */
public enum SmsEncoding {

    /** GSM 03.38 7-bit alphabet: 160 characters in a single SMS, 153 per concatenated part. */
    GSM7(160, 153),
    /** UCS-2 (Cyrillic and other non-GSM text): 70 characters, 67 per concatenated part. */
    UCS2(70, 67);

    private final int singleSegmentCapacity;
    private final int concatenatedSegmentCapacity;

    SmsEncoding(int singleSegmentCapacity, int concatenatedSegmentCapacity) {
        this.singleSegmentCapacity = singleSegmentCapacity;
        this.concatenatedSegmentCapacity = concatenatedSegmentCapacity;
    }

    public int singleSegmentCapacity() {
        return singleSegmentCapacity;
    }

    public int concatenatedSegmentCapacity() {
        return concatenatedSegmentCapacity;
    }
}
