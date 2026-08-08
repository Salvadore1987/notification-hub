package uz.hamkorbank.commhub.adapter.in.importer;

import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the initial template base comes from and who signs it off (FR-4.6).
 *
 * <p>Off by default: the import is a one-shot step of the roll-out, not something a restart should repeat
 * by accident. It is switched on for the run — a Kubernetes Job or a single pod with the file mounted — and
 * switched off again.
 *
 * @param enabled whether this instance imports the file at start-up
 * @param file path to the CSV inside the container
 * @param delimiter column separator; {@code ;} because that is what Excel writes in a RU locale
 * @param author name recorded as the author of every imported version (FR-4.2)
 * @param approver who publishes the imported versions; empty leaves them as drafts, which is the dry run
 */
@ConfigurationProperties("commhub.import.templates")
public record TemplateImportProperties(boolean enabled, String file, String delimiter, String author, String approver) {

    public static final String DEFAULT_DELIMITER = ";";
    public static final String DEFAULT_AUTHOR = "legacy-import";

    public TemplateImportProperties {
        delimiter = delimiter == null || delimiter.isEmpty() ? DEFAULT_DELIMITER : delimiter;
        author = author == null || author.isBlank() ? DEFAULT_AUTHOR : author;
        approver = approver == null || approver.isBlank() ? null : approver;
    }

    public static TemplateImportProperties disabled() {
        return new TemplateImportProperties(false, null, null, null, null);
    }

    public char separator() {
        return delimiter.charAt(0);
    }

    public Optional<String> approverOptional() {
        return Optional.ofNullable(approver);
    }
}
