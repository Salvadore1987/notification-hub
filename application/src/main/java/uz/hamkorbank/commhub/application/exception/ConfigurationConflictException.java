package uz.hamkorbank.commhub.application.exception;

/**
 * A configuration command contradicts what is already stored: a duplicate provider code, a provider
 * still listed in a fallback order, a stream registered twice (FR-2.1, FR-2.2).
 *
 * <p>An exception and not a verdict, unlike a pipeline rejection (IR-01): a rejected message is normal
 * traffic that the Bank's systems must handle, while a contradictory configuration edit is an operator
 * mistake in an interactive session. The REST adapter maps it onto 409.
 */
public class ConfigurationConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ConfigurationConflictException(String message) {
        super(message);
    }
}
