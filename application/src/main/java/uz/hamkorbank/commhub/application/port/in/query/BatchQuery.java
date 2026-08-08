package uz.hamkorbank.commhub.application.port.in.query;

import uz.hamkorbank.commhub.domain.model.vo.BatchId;
import uz.hamkorbank.commhub.domain.support.Guard;

/** Locates one batch with its progress (§8.2 {@code GET /batches/{id}}, FR-3.1). */
public record BatchQuery(BatchId batchId) {

    public BatchQuery {
        Guard.notNull(batchId, "BatchQuery.batchId");
    }

    public static BatchQuery byId(BatchId batchId) {
        return new BatchQuery(batchId);
    }
}
