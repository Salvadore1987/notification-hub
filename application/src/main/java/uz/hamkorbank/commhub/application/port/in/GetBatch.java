package uz.hamkorbank.commhub.application.port.in;

import uz.hamkorbank.commhub.application.dto.BatchView;
import uz.hamkorbank.commhub.application.exception.NotFoundException;
import uz.hamkorbank.commhub.application.port.in.query.BatchQuery;

/**
 * Returns the state and the progress of one batch (§8.2 {@code GET /batches/{id}}, FR-3.1).
 *
 * <p>The counters come from the aggregate, which the sending saga keeps up to date; the query walks no
 * messages, so asking about a batch of a million items costs one row.
 */
public interface GetBatch {

    /**
     * @throws NotFoundException when the batch does not exist; the REST adapter maps it onto 404
     */
    BatchView get(BatchQuery query);
}
