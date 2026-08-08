package uz.hamkorbank.commhub.application.port.in.query;

import java.time.Instant;
import uz.hamkorbank.commhub.domain.model.type.RejectionReason;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * A page of the dead-letter queue (§11.2 "DLQ", FR-3.3, UI-03).
 *
 * <p>The two flags are what separates the working screen from the archive: by default the list shows
 * what an operator can still act on, and the entries that were retried or archived are asked for
 * explicitly. {@code dlq_entry} has a partial index over exactly that predicate (V5), so the default
 * page is the cheap one.
 *
 * @param from {@code null} scans the whole queue; unlike {@code message} it is not partitioned and
 *     stays the size of what nobody has dealt with yet
 */
public record DlqQuery(
        Instant from,
        Instant to,
        RejectionReason reason,
        boolean includeRetried,
        boolean includeArchived,
        int limit,
        int offset) {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 500;

    public DlqQuery {
        Guard.isTrue(limit <= MAX_LIMIT, "DlqQuery.limit exceeds " + MAX_LIMIT);
        Guard.positive(limit, "DlqQuery.limit");
        Guard.notNegative(offset, "DlqQuery.offset");
        Guard.isTrue(from == null || to == null || !to.isBefore(from), "DlqQuery.to precedes DlqQuery.from");
    }

    /** What is still waiting for an operator, oldest first. */
    public static DlqQuery pending() {
        return new DlqQuery(null, null, null, false, false, DEFAULT_LIMIT, 0);
    }
}
