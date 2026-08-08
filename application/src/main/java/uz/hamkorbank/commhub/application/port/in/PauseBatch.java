package uz.hamkorbank.commhub.application.port.in;

import uz.hamkorbank.commhub.application.dto.BatchControlResult;
import uz.hamkorbank.commhub.application.port.in.command.BatchActionCommand;

/**
 * Pauses a batch (FR-3.2).
 *
 * <p>Messages already handed to a provider run to completion; nothing new is dispatched until the
 * batch is resumed.
 */
public interface PauseBatch {

    BatchControlResult pause(BatchActionCommand command);
}
