package uz.hamkorbank.commhub.domain.model.type;

/** How a source system submits messages (FR-1.1, FR-1.3). */
public enum IntegrationType {
    /** Primary asynchronous transport (§8.1). */
    KAFKA,
    /** Synchronous transport, including OTP (§8.2, FR-1.7). */
    REST
}
