package uz.hamkorbank.commhub.application.port.in;

import uz.hamkorbank.commhub.application.dto.MessageView;
import uz.hamkorbank.commhub.application.exception.NotFoundException;
import uz.hamkorbank.commhub.application.port.in.query.MessageQuery;

/**
 * Returns the state of one message to the system that submitted it (§8.2 {@code GET /messages}).
 *
 * <p>Read-only: the view carries the canonical status, the route and the status history, and never a
 * decrypted payload — a source system asks what happened to its message, not what was in it (DB-04).
 */
public interface GetMessage {

    /**
     * @throws NotFoundException when nothing matches the query; the REST adapter maps it onto 404
     */
    MessageView get(MessageQuery query);
}
