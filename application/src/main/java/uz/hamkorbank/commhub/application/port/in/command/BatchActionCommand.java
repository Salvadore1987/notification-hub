package uz.hamkorbank.commhub.application.port.in.command;

import java.util.Optional;
import uz.hamkorbank.commhub.domain.model.Actor;
import uz.hamkorbank.commhub.domain.model.vo.BatchId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Pause, resume or stop of one batch (FR-3.2, §8.2 {@code /batches/{id}/actions/...}).
 *
 * @param actor operator or source system requesting the action; recorded in the audit log (FR-7.3)
 * @param reason free-text justification shown in the admin panel and the audit log
 */
public record BatchActionCommand(BatchId batchId, Actor actor, String reason) {

    public BatchActionCommand {
        Guard.notNull(batchId, "BatchActionCommand.batchId");
        Guard.notNull(actor, "BatchActionCommand.actor");
    }

    public static BatchActionCommand of(BatchId batchId, Actor actor) {
        return new BatchActionCommand(batchId, actor, null);
    }

    public Optional<String> reasonOptional() {
        return Optional.ofNullable(reason);
    }
}
