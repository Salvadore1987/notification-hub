package uz.hamkorbank.commhub.domain.model.type;

/** Why a recipient address or client is suppressed (FR-5.1, EM-02, §18.2). */
public enum SuppressionReason {
    OPT_OUT,
    COMPLAINT,
    HARD_BOUNCE,
    /** Repeated delivery failures for the address. */
    DELIVERY_FAILURES,
    /** Reported as blacklisted by a provider (SMS Gate code 7/20). */
    PROVIDER_BLACKLIST,
    MANUAL
}
