package uz.hamkorbank.commhub.domain.model.type;

/**
 * Classification of a provider error, decided by the provider adapter (PM-01, §18.1, §18.2).
 *
 * <p>Drives the retry policy of the sending saga: retry, give up, or trip the circuit breaker.
 */
public enum ErrorClass {

    /** Transient: retry with exponential backoff and jitter (PR-01). */
    RETRYABLE,
    /** Permanent for this message: no retry, terminal non-delivery. */
    NON_RETRYABLE,
    /** Provider-wide problem (e.g. Playmobile 102 Account lock): open the circuit breaker + alert. */
    BLOCKING
}
