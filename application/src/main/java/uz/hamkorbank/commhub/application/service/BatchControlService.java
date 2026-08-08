package uz.hamkorbank.commhub.application.service;

import java.time.Instant;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.hamkorbank.commhub.application.dto.BatchControlResult;
import uz.hamkorbank.commhub.application.exception.NotFoundException;
import uz.hamkorbank.commhub.application.mapper.BatchMapper;
import uz.hamkorbank.commhub.application.port.in.PauseBatch;
import uz.hamkorbank.commhub.application.port.in.ResumeBatch;
import uz.hamkorbank.commhub.application.port.in.StartBatch;
import uz.hamkorbank.commhub.application.port.in.StopBatch;
import uz.hamkorbank.commhub.application.port.in.command.BatchActionCommand;
import uz.hamkorbank.commhub.application.port.out.AuditEntry;
import uz.hamkorbank.commhub.application.port.out.AuditPort;
import uz.hamkorbank.commhub.application.port.out.BatchRepository;
import uz.hamkorbank.commhub.application.port.out.ClockPort;
import uz.hamkorbank.commhub.domain.model.Batch;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Pause, resume and stop of a batch (FR-3.2, FR-3.1).
 *
 * <p>All three are O(1): only the state of the batch changes. The messages themselves are handled
 * lazily by the sending saga, which re-reads the batch on every turn — a paused batch defers its
 * messages, a stopped one cancels them (FR-3.2). That is what makes stopping a batch of a million
 * items instantaneous instead of a mass update.
 *
 * <p>Every action is written to the audit journal with its actor (FR-7.3, SEC-08).
 */
@Service
public class BatchControlService implements StartBatch, PauseBatch, ResumeBatch, StopBatch {

    private static final String ENTITY_TYPE = "batch";

    private final ClockPort clock;
    private final BatchRepository batches;
    private final AuditPort audit;
    private final BatchMapper mapper;

    public BatchControlService(ClockPort clock, BatchRepository batches, AuditPort audit, BatchMapper mapper) {
        this.clock = Guard.notNull(clock, "clock");
        this.batches = Guard.notNull(batches, "batches");
        this.audit = Guard.notNull(audit, "audit");
        this.mapper = Guard.notNull(mapper, "mapper");
    }

    @Override
    @Transactional
    public BatchControlResult start(BatchActionCommand command) {
        return apply(command, "batch.start", Batch::startProcessing);
    }

    @Override
    @Transactional
    public BatchControlResult pause(BatchActionCommand command) {
        return apply(command, "batch.pause", Batch::pause);
    }

    @Override
    @Transactional
    public BatchControlResult resume(BatchActionCommand command) {
        return apply(command, "batch.resume", Batch::resume);
    }

    @Override
    @Transactional
    public BatchControlResult stop(BatchActionCommand command) {
        return apply(command, "batch.stop", Batch::stop);
    }

    private BatchControlResult apply(BatchActionCommand command, String action, Consumer<Batch> transition) {
        Guard.notNull(command, "command");
        Instant now = clock.now();
        Batch batch = batches.findById(command.batchId())
                .orElseThrow(() -> NotFoundException.of(ENTITY_TYPE, command.batchId()));
        String before = batch.status().name();
        transition.accept(batch);
        batches.save(batch);
        audit.write(new AuditEntry(
                command.actor(),
                action,
                ENTITY_TYPE,
                batch.id().toString(),
                before,
                batch.status().name(),
                null,
                now));
        return mapper.toControlResult(batch);
    }
}
