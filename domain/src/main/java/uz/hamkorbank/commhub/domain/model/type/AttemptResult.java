package uz.hamkorbank.commhub.domain.model.type;

/** Outcome of a single provider call recorded on a delivery attempt (§6.1, PR-03). */
public enum AttemptResult {
    /** The call is in flight. */
    PENDING,
    /** The provider acknowledged the submission. */
    ACCEPTED,
    /** The provider rejected the submission with a business error code. */
    REJECTED,
    /** Transport or server error. */
    ERROR,
    /** Connect or read timeout (PR-01). */
    TIMEOUT
}
