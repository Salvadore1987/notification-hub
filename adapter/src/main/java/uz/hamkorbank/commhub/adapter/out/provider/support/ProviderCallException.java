package uz.hamkorbank.commhub.adapter.out.provider.support;

import uz.hamkorbank.commhub.domain.model.type.ErrorClass;

/**
 * A provider call that did not produce an answer the adapter can read (PR-01).
 *
 * <p>Exists so the retry and the circuit breaker have something to see: {@link ProviderCallExecutor}
 * only retries and only counts failures for what is thrown, which keeps a business rejection — a
 * perfectly successful HTTP exchange that says "this content is not allowed" — from ever opening a
 * breaker.
 *
 * <p>Never leaves the adapter: the executor turns it into a {@code ProviderAck} with the class carried
 * here, and the sending saga sees only the ack (PR-01).
 *
 * @param responseCode code the ack will carry: an HTTP status, a provider code, or a symbolic name
 */
public class ProviderCallException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public static final String TIMEOUT_CODE = "TIMEOUT";
    public static final String TRANSPORT_CODE = "TRANSPORT";

    private final String responseCode;
    private final ErrorClass errorClass;
    private final boolean timeout;

    public ProviderCallException(String responseCode, ErrorClass errorClass, String message, Throwable cause) {
        super(message, cause);
        this.responseCode = responseCode;
        this.errorClass = errorClass;
        this.timeout = TIMEOUT_CODE.equals(responseCode);
    }

    /** The provider did not answer in time; always retryable (PR-01). */
    public static ProviderCallException timeout(String detail, Throwable cause) {
        return new ProviderCallException(TIMEOUT_CODE, ErrorClass.RETRYABLE, detail, cause);
    }

    /** The connection failed or the answer was unreadable. */
    public static ProviderCallException transport(String detail, Throwable cause) {
        return new ProviderCallException(TRANSPORT_CODE, ErrorClass.RETRYABLE, detail, cause);
    }

    /** The provider answered with a code that is transient on its side, e.g. HTTP 5xx or §18.1 100. */
    public static ProviderCallException retryable(String responseCode, String detail) {
        return new ProviderCallException(responseCode, ErrorClass.RETRYABLE, detail, null);
    }

    /**
     * The provider reported a problem with the account or the integration itself, not with the message
     * (§18.1 code 102 Account lock). Opens the breaker at once and fails the message over (FR-6.3).
     */
    public static ProviderCallException blocking(String responseCode, String detail) {
        return new ProviderCallException(responseCode, ErrorClass.BLOCKING, detail, null);
    }

    public String responseCode() {
        return responseCode;
    }

    public ErrorClass errorClass() {
        return errorClass;
    }

    public boolean isTimeout() {
        return timeout;
    }

    public boolean isBlocking() {
        return errorClass == ErrorClass.BLOCKING;
    }
}
