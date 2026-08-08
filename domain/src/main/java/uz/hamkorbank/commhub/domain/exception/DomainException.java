package uz.hamkorbank.commhub.domain.exception;

/**
 * Base type for every error raised by the domain layer.
 *
 * <p>Application-layer code maps these onto transport error codes (IR-01); the domain itself never
 * references transport concerns.
 */
public abstract class DomainException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    protected DomainException(String message) {
        super(message);
    }

    protected DomainException(String message, Throwable cause) {
        super(message, cause);
    }
}
