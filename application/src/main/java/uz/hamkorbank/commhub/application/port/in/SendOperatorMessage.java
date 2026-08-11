package uz.hamkorbank.commhub.application.port.in;

import uz.hamkorbank.commhub.application.dto.OperatorBatchResult;
import uz.hamkorbank.commhub.application.dto.SubmitMessageResult;
import uz.hamkorbank.commhub.application.port.in.command.OperatorBatchCommand;
import uz.hamkorbank.commhub.application.port.in.command.OperatorSendCommand;

/**
 * Sending initiated by an operator from the admin panel (ADR-0038, §11.2, FR-7.3).
 *
 * <p>Two operations rather than two interfaces, for the reason a {@code Manage…} use case groups the
 * CRUD of one aggregate: they share the actor, the justification, the template resolution and the audit
 * entry, and separating them would only duplicate all four.
 *
 * <p>Both go through the very same {@code SubmitMessage} / {@code SubmitBatch} the Bank's systems use.
 * What differs is who is allowed to call it, that a justification is required, and that the content can
 * only come from a published template.
 */
public interface SendOperatorMessage {

    /** One message to one recipient. */
    SubmitMessageResult send(OperatorSendCommand command);

    /** A batch from an uploaded recipient list; the batch is created and its items are uploaded. */
    OperatorBatchResult sendBatch(OperatorBatchCommand command);
}
