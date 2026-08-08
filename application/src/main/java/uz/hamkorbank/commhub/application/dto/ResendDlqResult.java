package uz.hamkorbank.commhub.application.dto;

import java.util.List;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Outcome of a manual DLQ retry, single or bulk (FR-3.3).
 *
 * @param skipped entries that could not be retried: already retried, archived or gone
 */
public record ResendDlqResult(List<MessageId> requeued, List<MessageId> skipped) {

    public ResendDlqResult {
        requeued = Guard.copyOf(requeued);
        skipped = Guard.copyOf(skipped);
    }

    public int requeuedCount() {
        return requeued.size();
    }

    public int skippedCount() {
        return skipped.size();
    }
}
