package uz.hamkorbank.commhub.application.port.out;

import java.util.List;
import java.util.Optional;
import uz.hamkorbank.commhub.application.port.in.query.BatchListQuery;
import uz.hamkorbank.commhub.domain.model.Batch;
import uz.hamkorbank.commhub.domain.model.type.BatchStatus;
import uz.hamkorbank.commhub.domain.model.vo.BatchId;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;

/** Persistence of the {@code Batch} aggregate (§10.1 {@code batch}, FR-1.6, FR-3.1). */
public interface BatchRepository {

    Batch save(Batch batch);

    Optional<Batch> findById(BatchId batchId);

    List<Batch> findByStream(StreamId streamId, BatchStatus status, int limit);

    /** One page of the batch list, most recent first (§11.2 "Рассылки", UI-03). */
    List<Batch> search(BatchListQuery query);

    /** Total number of matching batches, so the screen can page. */
    long count(BatchListQuery query);

    /**
     * Applies a counter change atomically and returns the progress that resulted (FR-3.1, ADR-0040).
     *
     * <p>Not through {@link #save(Batch)}: that writes the counters as absolute values read a moment
     * earlier, so two dispatch threads working on the same batch lose one of their increments. On a
     * batch of a million items "lose one" is not one — it is percentages.
     */
    Batch.Progress applyProgress(BatchId batchId, Batch.Delta delta);

    /**
     * Closes a fully processed batch; a no-op unless it is still {@code PROCESSING} (FR-3.1).
     *
     * <p>Idempotent by the status predicate, so the message that happens to be last may try more than
     * once without turning a stopped batch into a completed one.
     */
    void markCompleted(BatchId batchId);
}
