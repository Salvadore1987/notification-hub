package uz.hamkorbank.commhub.application.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.hamkorbank.commhub.application.dto.BatchView;
import uz.hamkorbank.commhub.application.exception.NotFoundException;
import uz.hamkorbank.commhub.application.mapper.BatchMapper;
import uz.hamkorbank.commhub.application.port.in.GetBatch;
import uz.hamkorbank.commhub.application.port.in.query.BatchQuery;
import uz.hamkorbank.commhub.application.port.out.BatchRepository;
import uz.hamkorbank.commhub.domain.support.Guard;

/** Answers "how far along is my batch" (§8.2 {@code GET /batches/{id}}, FR-3.1). */
@Service
public class BatchQueryService implements GetBatch {

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
}
