package uz.hamkorbank.commhub.application.port.in;

import uz.hamkorbank.commhub.application.dto.StreamControlResult;
import uz.hamkorbank.commhub.application.port.in.command.StreamActionCommand;

/** Reactivates a suspended inbound stream (FR-3.2, FR-1.3). */
public interface ResumeStream {

    StreamControlResult resume(StreamActionCommand command);
}
