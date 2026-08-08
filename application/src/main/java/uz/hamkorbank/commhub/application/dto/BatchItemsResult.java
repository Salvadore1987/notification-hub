package uz.hamkorbank.commhub.application.dto;

import java.util.List;
import uz.hamkorbank.commhub.domain.model.type.RejectionReason;
import uz.hamkorbank.commhub.domain.model.vo.BatchId;
import uz.hamkorbank.commhub.domain.model.vo.ExternalMessageId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Answer to one uploaded chunk of batch items (FR-1.6, §8.2 {@code POST /batches/{id}/items}).
 *
 * <p>Item-level failures never fail the whole chunk: accepted items keep flowing and the rejected
 * ones are reported individually, each with its canonical reason (FR-1.4, IR-01).
 */
public record BatchItemsResult(
        BatchId batchId, long accepted, long duplicates, List<ItemRejection> rejections, BatchProgressDto progress) {

    public BatchItemsResult {
        Guard.notNull(batchId, "BatchItemsResult.batchId");
        Guard.notNegative(accepted, "BatchItemsResult.accepted");
        Guard.notNegative(duplicates, "BatchItemsResult.duplicates");
        rejections = Guard.copyOf(rejections);
        Guard.notNull(progress, "BatchItemsResult.progress");
    }

    public long rejected() {
        return rejections.size();
    }

    /** One rejected item of the chunk (FR-1.4). */
    public record ItemRejection(ExternalMessageId externalMessageId, RejectionReason reason, String detail) {

        public ItemRejection {
            Guard.notNull(externalMessageId, "ItemRejection.externalMessageId");
            Guard.notNull(reason, "ItemRejection.reason");
        }
    }
}
