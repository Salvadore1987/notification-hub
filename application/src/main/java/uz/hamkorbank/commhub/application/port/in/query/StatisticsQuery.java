package uz.hamkorbank.commhub.application.port.in.query;

import java.time.Instant;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.vo.BatchId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * One report over the sends of a period (§11.2 "Статистика/Отчёты", FR-6.2, UI-03).
 *
 * <p>Filters narrow, the dimension groups, and the two are independent: "cost per provider for one
 * stream" is a stream filter with a provider dimension. The period is mandatory — it is the partition
 * key of {@code message} (DB-02) — and it is what bounds an {@code HOUR} report to a readable size.
 *
 * <p>{@code includeTest} defaults to false at every call site that matters. FR-7.4 asks for test sends
 * to be kept out of the statistics, and the way this system honours that is a dimension rather than a
 * deletion: the rows exist, the report leaves them out unless the operator who ran the test asks for
 * them back.
 */
public record StatisticsQuery(
        Instant from,
        Instant to,
        StatisticsDimension dimension,
        Channel channel,
        StreamId streamId,
        ProviderCode provider,
        BatchId batchId,
        boolean includeTest) {

    public StatisticsQuery {
        Guard.notNull(from, "StatisticsQuery.from");
        Guard.notNull(to, "StatisticsQuery.to");
        Guard.notNull(dimension, "StatisticsQuery.dimension");
        Guard.isTrue(!to.isBefore(from), "StatisticsQuery.to precedes StatisticsQuery.from");
    }

    /** Volumes of the period broken down the given way, test sends excluded (FR-7.4). */
    public static StatisticsQuery of(Instant from, Instant to, StatisticsDimension dimension) {
        return new StatisticsQuery(from, to, dimension, null, null, null, null, false);
    }

    /** The same report over one batch, which is what the batch card shows (§11.2 "Рассылки"). */
    public static StatisticsQuery ofBatch(Instant from, Instant to, BatchId batchId) {
        return new StatisticsQuery(from, to, StatisticsDimension.CHANNEL, null, null, null, batchId, false);
    }
}
