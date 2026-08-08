package uz.hamkorbank.commhub.application.dto;

import java.time.Instant;
import java.util.Optional;
import uz.hamkorbank.commhub.domain.model.type.BatchStatus;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.vo.BatchId;
import uz.hamkorbank.commhub.domain.model.vo.Money;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * State and progress of one batch (§8.2 {@code GET /batches/{id}}, FR-1.6, FR-3.1).
 *
 * @param total announced number of items; grows while chunks are still being uploaded
 * @param costEstimate {@code null} until the tariffs of the routed provider are known (FR-6.2)
 */
public record BatchView(
        BatchId batchId,
        StreamId streamId,
        Channel channel,
        BatchStatus status,
        long total,
        BatchProgressDto progress,
        Instant createdAt,
        Money costEstimate) {

    public BatchView {
        Guard.notNull(batchId, "BatchView.batchId");
        Guard.notNull(streamId, "BatchView.streamId");
        Guard.notNull(channel, "BatchView.channel");
        Guard.notNull(status, "BatchView.status");
        Guard.notNegative(total, "BatchView.total");
        Guard.notNull(progress, "BatchView.progress");
        Guard.notNull(createdAt, "BatchView.createdAt");
    }

    public Optional<Money> costEstimateOptional() {
        return Optional.ofNullable(costEstimate);
    }
}
