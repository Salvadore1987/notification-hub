package uz.hamkorbank.commhub.domain.model.type;

/** Operational status of an inbound stream, i.e. a registered source system (FR-1.3, FR-3.2). */
public enum StreamStatus {
    ACTIVE,
    /** Temporarily suspended by an operator: submissions are rejected with {@code STREAM_SUSPENDED}. */
    SUSPENDED,
    DISABLED;

    public boolean acceptsTraffic() {
        return this == ACTIVE;
    }
}
