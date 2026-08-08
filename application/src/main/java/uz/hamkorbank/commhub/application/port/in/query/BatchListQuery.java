package uz.hamkorbank.commhub.application.port.in.query;

import java.time.Instant;
import uz.hamkorbank.commhub.domain.model.type.BatchStatus;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * A page of the batch list of the admin panel (§11.2 "Рассылки", FR-3.1, UI-03).
 *
 * <p>Every filter is optional; the period is not, for the same reason the message list carries one — a
 * batch list without a horizon grows with the history of the Hub and is read on the busiest screen.
 *
 * <p>{@code activeOnly} is the dashboard's question and deliberately not a status filter: "active" is
 * every non-terminal {@link BatchStatus}, and a caller that spells the set out is a caller that will
 * still be spelling out the old set after a status is added.
 */
public record BatchListQuery(
        Instant from,
        Instant to,
        StreamId streamId,
        Channel channel,
        BatchStatus status,
        boolean activeOnly,
        int limit,
        int offset) {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;

    public BatchListQuery {
        Guard.notNull(from, "BatchListQuery.from");
        Guard.notNull(to, "BatchListQuery.to");
        Guard.isTrue(!to.isBefore(from), "BatchListQuery.to precedes BatchListQuery.from");
        Guard.isTrue(limit <= MAX_LIMIT, "BatchListQuery.limit exceeds " + MAX_LIMIT);
        Guard.positive(limit, "BatchListQuery.limit");
        Guard.notNegative(offset, "BatchListQuery.offset");
        Guard.isTrue(
                !activeOnly || status == null,
                "BatchListQuery cannot ask for activeOnly and a single status at the same time");
    }

    public static BatchListQuery ofPeriod(Instant from, Instant to) {
        return new BatchListQuery(from, to, null, null, null, false, DEFAULT_LIMIT, 0);
    }

    /** The batches still moving, which is what the dashboard shows (§11.2 "Дашборд"). */
    public static BatchListQuery active(Instant from, Instant to, int limit) {
        return new BatchListQuery(from, to, null, null, null, true, limit, 0);
    }
}
