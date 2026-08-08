package uz.hamkorbank.commhub.application.port.in;

import java.util.List;
import uz.hamkorbank.commhub.application.dto.DlqEntryView;
import uz.hamkorbank.commhub.application.port.in.query.DlqQuery;

/** The dead-letter queue screen (§11.2 "DLQ", FR-3.3, UI-03). */
public interface GetDlq {

    /** One page of the queue, oldest first — the order it has to be worked through. */
    List<DlqEntryView> list(DlqQuery query);

    long count(DlqQuery query);
}
