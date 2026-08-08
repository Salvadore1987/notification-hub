package uz.hamkorbank.commhub.application.dto;

import uz.hamkorbank.commhub.domain.model.type.BatchStatus;
import uz.hamkorbank.commhub.domain.model.vo.BatchId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Outcome of a pause, resume or stop of a batch (FR-3.2).
 *
 * <p>Stopping does not walk the messages of the batch: the sending saga cancels each remaining
 * message when it picks it up, so the operation stays O(1) for a batch of a million items.
 */
public record BatchControlResult(BatchId batchId, BatchStatus status, BatchProgressDto progress) {

    public BatchControlResult {
        Guard.notNull(batchId, "BatchControlResult.batchId");
        Guard.notNull(status, "BatchControlResult.status");
        Guard.notNull(progress, "BatchControlResult.progress");
    }
}
