package uz.hamkorbank.commhub.application.port.in;

import uz.hamkorbank.commhub.application.dto.BatchControlResult;
import uz.hamkorbank.commhub.application.port.in.command.BatchActionCommand;

/**
 * Stops a batch for good (FR-3.2).
 *
 * <p>Terminal for the batch: its remaining messages are cancelled by the sending saga as they are
 * picked up, so stopping a batch of a million items costs one update.
 */
public interface StopBatch {

    BatchControlResult stop(BatchActionCommand command);
}
