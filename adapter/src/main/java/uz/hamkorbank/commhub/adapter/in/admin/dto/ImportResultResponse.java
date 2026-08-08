package uz.hamkorbank.commhub.adapter.in.admin.dto;

import java.util.List;

/**
 * Outcome of a CSV upload into the suppression list (§11.2 "Suppression list", FR-5.1).
 *
 * <p>Reports rather than fails: a file of four hundred addresses converted by hand will contain a few
 * bad lines, and finding them one upload at a time is the slowest possible way to migrate a list.
 *
 * @param skipped rows whose entry already existed; re-uploading the same file changes nothing
 */
public record ImportResultResponse(int imported, int skipped, List<FailureDto> failures) {

    /** @param line 1-based line of the file, so the operator can find it in their spreadsheet */
    public record FailureDto(int line, String reason) {}
}
