package uz.hamkorbank.commhub.application.port.in;

import uz.hamkorbank.commhub.application.dto.BatchControlResult;
import uz.hamkorbank.commhub.application.port.in.command.BatchActionCommand;

/**
 * Starts an accepted batch (FR-1.6, §8.2 {@code /batches/{id}/actions/start}).
 *
 * <p>Only needed when the source system uploads every chunk first and starts the send afterwards: a
 * batch whose items arrive without an explicit start begins processing with its first chunk, so this
 * transition has already happened by the time the items are in.
 */
public interface StartBatch {

    BatchControlResult start(BatchActionCommand command);
}
