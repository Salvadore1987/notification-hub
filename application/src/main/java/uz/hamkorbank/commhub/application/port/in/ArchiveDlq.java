package uz.hamkorbank.commhub.application.port.in;

import uz.hamkorbank.commhub.application.dto.ArchiveDlqResult;
import uz.hamkorbank.commhub.application.port.in.command.ArchiveDlqCommand;

/**
 * Taking DLQ entries off the working list without retrying them (FR-3.3, §11.2 "DLQ").
 *
 * <p>Its own use case rather than a second method on {@link ResendDlq}, because it is a different
 * decision with a different consequence: a retry puts a message back into the pipeline, archiving says
 * nobody will. Both are journalled, and the archived entry stays in the table — the queue is evidence of
 * what did not get delivered, and shortening it by deleting is how that evidence disappears.
 */
public interface ArchiveDlq {

    ArchiveDlqResult archive(ArchiveDlqCommand command);
}
