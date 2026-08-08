package uz.hamkorbank.commhub.application.dto;

import uz.hamkorbank.commhub.domain.model.type.BatchStatus;
import uz.hamkorbank.commhub.domain.model.vo.BatchId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Answer to a batch header submission (FR-1.6, §8.2 {@code POST /batches}).
 *
 * <p>The batch is visible from this moment on; its items may still be uploaded in chunks (FR-1.6).
 */
public record BatchAcceptedResult(BatchId batchId, BatchStatus status, long total) {

    public BatchAcceptedResult {
        Guard.notNull(batchId, "BatchAcceptedResult.batchId");
        Guard.notNull(status, "BatchAcceptedResult.status");
        Guard.notNegative(total, "BatchAcceptedResult.total");
    }
}
