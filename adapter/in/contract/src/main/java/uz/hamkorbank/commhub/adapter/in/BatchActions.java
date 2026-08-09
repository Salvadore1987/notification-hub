package uz.hamkorbank.commhub.adapter.in;

import org.springframework.stereotype.Component;
import uz.hamkorbank.commhub.application.port.in.PauseBatch;
import uz.hamkorbank.commhub.application.port.in.ResumeBatch;
import uz.hamkorbank.commhub.application.port.in.StartBatch;
import uz.hamkorbank.commhub.application.port.in.StopBatch;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * The four batch actions of §8.2 bundled into one dependency.
 *
 * <p>They are four input ports by design — one use case per action (AR-06) — and injecting them
 * separately would take the controller past the eight constructor parameters this project allows. This
 * is a record of ports and holds no logic: which action a request means is decided by the controller,
 * exactly as the routing of any other path variable is.
 */
@Component
public record BatchActions(StartBatch start, PauseBatch pause, ResumeBatch resume, StopBatch stop) {

    public BatchActions {
        Guard.notNull(start, "BatchActions.start");
        Guard.notNull(pause, "BatchActions.pause");
        Guard.notNull(resume, "BatchActions.resume");
        Guard.notNull(stop, "BatchActions.stop");
    }
}
