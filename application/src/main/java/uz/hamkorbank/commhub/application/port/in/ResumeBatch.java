package uz.hamkorbank.commhub.application.port.in;

import uz.hamkorbank.commhub.application.dto.BatchControlResult;
import uz.hamkorbank.commhub.application.port.in.command.BatchActionCommand;

/** Resumes a paused batch (FR-3.2). */
public interface ResumeBatch {

    BatchControlResult resume(BatchActionCommand command);
}
