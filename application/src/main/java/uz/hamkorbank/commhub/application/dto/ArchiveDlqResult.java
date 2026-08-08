package uz.hamkorbank.commhub.application.dto;

import java.util.List;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Outcome of archiving DLQ entries, single or in bulk (FR-3.3, §11.2 "DLQ").
 *
 * <p>Shaped like {@link ResendDlqResult} on purpose: both are the same gesture on the same screen — a
 * filtered list, a selection, one button — and an operator reading two differently shaped answers to
 * two buttons next to each other is an operator who misreads one of them.
 *
 * @param skipped entries that were not there to archive, or were archived already
 */
public record ArchiveDlqResult(List<MessageId> archived, List<MessageId> skipped) {

    public ArchiveDlqResult {
        archived = Guard.copyOf(archived);
        skipped = Guard.copyOf(skipped);
    }

    public int archivedCount() {
        return archived.size();
    }

    public int skippedCount() {
        return skipped.size();
    }
}
