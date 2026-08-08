package uz.hamkorbank.commhub.application.service;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.hamkorbank.commhub.application.dto.BatchView;
import uz.hamkorbank.commhub.application.exception.NotFoundException;
import uz.hamkorbank.commhub.application.mapper.BatchMapper;
import uz.hamkorbank.commhub.application.port.in.GetBatch;
import uz.hamkorbank.commhub.application.port.in.GetBatches;
import uz.hamkorbank.commhub.application.port.in.query.BatchListQuery;
import uz.hamkorbank.commhub.application.port.in.query.BatchQuery;
import uz.hamkorbank.commhub.application.port.out.BatchRepository;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Answers "how far along is my batch" (§8.2 {@code GET /batches/{id}}, FR-3.1) and the list behind it
 * (§11.2 "Рассылки").
 *
 * <p>Both use cases on one service because the card and the list read the same aggregate through the
 * same mapper: the panel drills from one into the other, and a batch that shows a different progress in
 * the list than on its card is the bug nobody can reproduce.
 */
@Service
public class BatchQueryService implements GetBatch, GetBatches {

    private static final String ENTITY_TYPE = "batch";

    private final BatchRepository batches;
    private final BatchMapper mapper;

    public BatchQueryService(BatchRepository batches, BatchMapper mapper) {
        this.batches = Guard.notNull(batches, "batches");
        this.mapper = Guard.notNull(mapper, "mapper");
    }

    @Override
    @Transactional(readOnly = true)
    public BatchView get(BatchQuery query) {
        Guard.notNull(query, "query");
        return batches.findById(query.batchId())
                .map(mapper::toView)
                .orElseThrow(() -> NotFoundException.of(ENTITY_TYPE, query.batchId()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<BatchView> list(BatchListQuery query) {
        Guard.notNull(query, "query");
        return batches.search(query).stream().map(mapper::toView).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long count(BatchListQuery query) {
        Guard.notNull(query, "query");
        return batches.count(query);
    }
}
