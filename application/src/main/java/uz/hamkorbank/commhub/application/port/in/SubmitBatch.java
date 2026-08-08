package uz.hamkorbank.commhub.application.port.in;

import uz.hamkorbank.commhub.application.dto.BatchAcceptedResult;
import uz.hamkorbank.commhub.application.dto.BatchItemsResult;
import uz.hamkorbank.commhub.application.port.in.command.AddBatchItemsCommand;
import uz.hamkorbank.commhub.application.port.in.command.CreateBatchCommand;

/**
 * Accepts a batch send: the header first, the items in chunks afterwards (FR-1.6).
 *
 * <p>The batch is visible with its progress from the moment the header is accepted; each item is
 * expanded into a normal submission and therefore goes through the same pipeline as a single message
 * (FR-3.1, PU-10).
 */
public interface SubmitBatch {

    /** Accepts the batch header (§8.2 {@code POST /batches}). */
    BatchAcceptedResult create(CreateBatchCommand command);

    /** Accepts one chunk of items (§8.2 {@code POST /batches/{id}/items}). */
    BatchItemsResult addItems(AddBatchItemsCommand command);
}
