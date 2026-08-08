package uz.hamkorbank.commhub.domain.model.type;

/**
 * Message priority inside its traffic class (SRS §5.2).
 *
 * <p>Provider adapters map these onto provider-specific values, e.g. Playmobile
 * {@code low|normal|high|realtime} (PM-03) and SMS Gate {@code weight} 0–10 (SG-01).
 */
public enum Priority {
    LOW,
    NORMAL,
    HIGH,
    REALTIME;

    /** Higher value = sent earlier; used by queue ordering in the application layer. */
    public int rank() {
        return ordinal();
    }

    public boolean isAtLeast(Priority other) {
        return rank() >= other.rank();
    }
}
