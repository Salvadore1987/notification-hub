package uz.hamkorbank.commhub.application.port.in;

import uz.hamkorbank.commhub.application.dto.ResendDlqResult;
import uz.hamkorbank.commhub.application.port.in.command.ResendDlqCommand;

/**
 * Manual retry of messages that ended up in the DLQ (FR-3.3).
 *
 * <p>The message returns to {@code QUEUED} and the lifecycle resumes with a new delivery attempt
 * (ST-02); an entry can be retried once, after which it may only be archived.
 */
public interface ResendDlq {

    ResendDlqResult resend(ResendDlqCommand command);
}
