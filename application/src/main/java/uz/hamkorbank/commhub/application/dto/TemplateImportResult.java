package uz.hamkorbank.commhub.application.dto;

import java.util.List;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Outcome of importing the Bank's existing template base (FR-4.6).
 *
 * <p>Counted per row, not per template: one file row is one localised body, and the operator running the
 * migration needs to reconcile the count with the file they were handed.
 *
 * @param created rows that produced a new template card
 * @param imported rows that produced a new version of a template
 * @param skipped rows whose text already exists as the published version — a re-run of the same file
 *     imports nothing, which is what makes the migration repeatable
 * @param failures rows the Hub refused, with the reason; the rest of the file is still imported
 */
public record TemplateImportResult(int created, int imported, int skipped, List<Failure> failures) {

    public TemplateImportResult {
        Guard.notNegative(created, "TemplateImportResult.created");
        Guard.notNegative(imported, "TemplateImportResult.imported");
        Guard.notNegative(skipped, "TemplateImportResult.skipped");
        failures = Guard.copyOf(failures);
    }

    public boolean hasFailures() {
        return !failures.isEmpty();
    }

    /**
     * One rejected row (FR-4.6).
     *
     * <p>{@code code} and {@code locale} are plain strings: a row can fail precisely because its code or
     * its locale is not something the Hub recognises, and a report that cannot name the row it is about is
     * of no use to whoever has to fix the file.
     */
    public record Failure(String code, String locale, String reason) {

        public Failure {
            Guard.notBlank(reason, "Failure.reason");
        }
    }
}
