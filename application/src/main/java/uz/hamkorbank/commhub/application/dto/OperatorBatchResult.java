package uz.hamkorbank.commhub.application.dto;

import java.util.List;
import uz.hamkorbank.commhub.application.dto.BatchItemsResult.ItemRejection;
import uz.hamkorbank.commhub.domain.model.vo.BatchId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * What came of a batch the operator sent from the panel (ADR-0038, FR-1.4).
 *
 * <p>Per-row refusals are reported and never fail the send: a list of fifty thousand recipients with
 * one suppressed number is forty-nine thousand nine hundred and ninety-nine messages plus one line the
 * operator can read.
 */
public record OperatorBatchResult(BatchId batchId, long accepted, long duplicates, List<ItemRejection> rejections) {

    public OperatorBatchResult {
        Guard.notNull(batchId, "OperatorBatchResult.batchId");
        Guard.notNegative(accepted, "OperatorBatchResult.accepted");
        Guard.notNegative(duplicates, "OperatorBatchResult.duplicates");
        rejections = Guard.copyOf(rejections);
    }

    public long rejected() {
        return rejections.size();
    }
}
