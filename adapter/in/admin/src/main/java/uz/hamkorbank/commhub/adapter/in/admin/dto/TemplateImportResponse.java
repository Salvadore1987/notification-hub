package uz.hamkorbank.commhub.adapter.in.admin.dto;

import java.util.List;

/**
 * Outcome of a CSV import of templates (§11.2 "Шаблоны", FR-4.6).
 *
 * <p>Bad rows are reported and the rest are imported: a file of two hundred templates with one broken
 * line is a file somebody has to fix one line of, not upload again.
 *
 * @param skipped rows whose wording already existed; the import is idempotent by wording
 */
public record TemplateImportResponse(int created, int imported, int skipped, List<FailureDto> failures) {

    public record FailureDto(String code, String locale, String reason) {}
}
