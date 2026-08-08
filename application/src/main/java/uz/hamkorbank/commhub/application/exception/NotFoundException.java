package uz.hamkorbank.commhub.application.exception;

/**
 * A command names an aggregate that does not exist; the REST adapter maps it onto 404 (IR-01).
 */
public class NotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public NotFoundException(String message) {
        super(message);
    }

    public static NotFoundException of(String entityType, Object id) {
        return new NotFoundException("%s %s does not exist".formatted(entityType, id));
    }
}
