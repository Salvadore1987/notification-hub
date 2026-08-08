package uz.hamkorbank.commhub.domain.exception;

/**
 * Raised when a status transition is not allowed by a domain state machine (ST-01, ST-02).
 *
 * <p>Applies to {@code Message}, {@code Batch} and {@code TemplateVersion} lifecycles.
 */
public final class InvalidStatusTransitionException extends DomainException {

    private static final long serialVersionUID = 1L;

    public InvalidStatusTransitionException(String message) {
        super(message);
    }

    public static InvalidStatusTransitionException of(String aggregate, Object from, Object to) {
        return new InvalidStatusTransitionException(
                "%s: transition %s -> %s is not allowed".formatted(aggregate, from, to));
    }
}
