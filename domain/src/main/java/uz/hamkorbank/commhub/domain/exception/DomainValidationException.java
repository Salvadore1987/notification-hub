package uz.hamkorbank.commhub.domain.exception;

/**
 * Raised when a value object or aggregate invariant is violated (FR-1.4).
 *
 * <p>The application layer maps this onto {@code VALIDATION_FAILED} (IR-01).
 */
public final class DomainValidationException extends DomainException {

    private static final long serialVersionUID = 1L;

    public DomainValidationException(String message) {
        super(message);
    }
}
