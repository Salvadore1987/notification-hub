package uz.hamkorbank.commhub.adapter.in.importer;

/**
 * The template file itself is unusable: no header, a required column missing, an unterminated quote
 * (FR-4.6).
 *
 * <p>Distinct from a bad row, which is reported and skipped. A file the codec cannot read at all has no
 * rows to report, so there is nothing to continue with.
 */
public class TemplateImportException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public TemplateImportException(String message) {
        super(message);
    }

    public TemplateImportException(String message, Throwable cause) {
        super(message, cause);
    }
}
