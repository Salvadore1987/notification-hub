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
}
