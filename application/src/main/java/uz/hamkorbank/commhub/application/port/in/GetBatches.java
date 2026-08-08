package uz.hamkorbank.commhub.application.port.in;

import java.util.List;
import uz.hamkorbank.commhub.application.dto.BatchView;
import uz.hamkorbank.commhub.application.port.in.query.BatchListQuery;

/** The batch list of the admin panel (§11.2 "Рассылки", FR-3.1, UI-03). */
public interface GetBatches {

    /** One page of matching batches, most recent first. */
    List<BatchView> list(BatchListQuery query);

    long count(BatchListQuery query);
}
