package uz.hamkorbank.commhub.domain.model.type;

/** Why a recipient address or client is suppressed (FR-5.1, EM-02, PU-04, §18.2). */
public enum SuppressionReason {
    OPT_OUT,
    COMPLAINT,
    HARD_BOUNCE,
    /** Repeated delivery failures for the address. */
    DELIVERY_FAILURES,
    /** Reported as blacklisted by a provider (SMS Gate code 7/20). */
    PROVIDER_BLACKLIST,
    /**
     * The platform declared a device token dead: FCM {@code UNREGISTERED}/{@code NOT_FOUND}, APNs
     * {@code 410 Unregistered} or {@code BadDeviceToken} (PU-04, PU-08).
     *
     * <p>Its own reason rather than {@link #PROVIDER_BLACKLIST}: an uninstalled application is not a
     * refusal to deliver, and the two are read differently — a blacklisted number is a question for the
     * provider, a dead token is a fact the source system has to mirror in its own registry.
     */
    PUSH_TOKEN_INVALID,
    MANUAL
}
