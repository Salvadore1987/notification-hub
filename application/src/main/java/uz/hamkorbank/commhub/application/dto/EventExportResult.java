package uz.hamkorbank.commhub.application.dto;

import java.time.Instant;

/**
 * Outcome of one export pass (FR-6.4).
 *
 * @param exported events published in this pass
 * @param position where the cursor stands afterwards; {@code null} when nothing was ever exported
 * @param exhausted whether the pass caught up with the data — false means the next tick has work waiting,
 *     which is what the operator watches during a backfill
 */
public record EventExportResult(int exported, Instant position, boolean exhausted) {

    public static EventExportResult nothing(Instant position) {
        return new EventExportResult(0, position, true);
    }
}
