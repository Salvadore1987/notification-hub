package uz.hamkorbank.commhub.adapter.in.contract;

/**
 * The received document does not satisfy the inbound contract (IK-03, §8.2).
 *
 * <p>Carries the pointer to the offending field so the caller is told what to fix rather than that
 * "the request was invalid": REST renders it as {@code problem+json} with code {@code VALIDATION_FAILED}
 * (IR-01), the Kafka consumers put the record on {@code comm.inbound.parse-error.v1} with the same text
 * in a header (IK-04).
 *
 * <p>Always non-retryable — a document that is wrong now is wrong on every redelivery.
 */
public class InboundContractException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String field;

    public InboundContractException(String field, String message) {
        super(field == null ? message : field + ": " + message);
        this.field = field;
    }

    public InboundContractException(String field, String message, Throwable cause) {
        super(field == null ? message : field + ": " + message, cause);
        this.field = field;
    }

    /** Required field of the contract is absent or empty. */
    public static InboundContractException missing(String field) {
        return new InboundContractException(field, "is required");
    }

    /** Field is present but its value is not one the contract allows. */
    public static InboundContractException invalid(String field, String detail) {
        return new InboundContractException(field, detail);
    }

    /** As {@link #invalid(String, String)}, keeping the rejection that produced it as the cause. */
    public static InboundContractException invalid(String field, String detail, Throwable cause) {
        return new InboundContractException(field, detail, cause);
    }

    /** JSON pointer of the offending field, {@code null} when the whole document is unreadable. */
    public String field() {
        return field;
    }
}
