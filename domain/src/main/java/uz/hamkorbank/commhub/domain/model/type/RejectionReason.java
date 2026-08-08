package uz.hamkorbank.commhub.domain.model.type;

/**
 * Machine-readable reason attached to a non-delivery status (ST-01).
 *
 * <p>The REST adapter maps these onto the {@code problem+json} error codes of IR-01.
 */
public enum RejectionReason {

    /** Payload, address or reference validation failed (FR-1.4). */
    VALIDATION_FAILED,
    /** Recipient is on the suppression list (FR-5.1). */
    SUPPRESSED,
    /** Recipient opted out of non-transactional traffic (FR-5.2). */
    OPT_OUT,
    /** Inside quiet hours and the configured behaviour is to reject (FR-5.3). */
    QUIET_HOURS,
    /** Frequency cap for the recipient is reached (FR-5.4). */
    FREQUENCY_CAPPED,
    /** Count or cost quota of the stream/channel/provider is exhausted (FR-2.6). */
    QUOTA_EXCEEDED,
    /** The source stream is suspended (FR-1.3, FR-3.2). */
    STREAM_SUSPENDED,
    /** No published template version for the requested code and locale (FR-4.1). */
    TEMPLATE_NOT_PUBLISHED,
    /** A merge field has no value in strict mode (FR-4.3). */
    TEMPLATE_VARIABLE_MISSING,
    /** No enabled channel or provider can serve the message (FR-2.2). */
    NO_ROUTE_AVAILABLE,
    /** Content contains a full card number (PCI DSS, SEC-05). */
    PAN_DETECTED,
    /** TTL elapsed before the message was handed to a provider (FR-3.4). */
    TTL_EXPIRED,
    /** Cancelled by a batch or stream stop (FR-3.2). */
    SEND_STOPPED,
    /** Cancelled by the global kill switch (FR-3.2). */
    KILL_SWITCH,
    /** Provider rejected the message permanently (§18.1, §18.2). */
    PROVIDER_REJECTED,
    /** All retries and fallbacks are exhausted (PR-01). */
    ATTEMPTS_EXHAUSTED,
    /** Same {@code (streamId, externalMessageId)} or {@code dedupKey} inside the window (FR-1.5). */
    DUPLICATE_SUBMISSION
}
