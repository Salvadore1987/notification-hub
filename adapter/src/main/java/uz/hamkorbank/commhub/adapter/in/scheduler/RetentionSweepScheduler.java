package uz.hamkorbank.commhub.adapter.in.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uz.hamkorbank.commhub.adapter.out.compliance.ComplianceProperties;
import uz.hamkorbank.commhub.application.port.out.ClockPort;
import uz.hamkorbank.commhub.application.port.out.DedupRegistryPort;
import uz.hamkorbank.commhub.application.port.out.FrequencyCounterPort;

/**
 * Clears the two tables that only ever hold a window of data: the dedup registry and the per-recipient
 * frequency counters (FR-1.5, FR-5.4, DB-03).
 *
 * <p>Neither is partitioned, on purpose — a 24-hour window does not need monthly sections — which means
 * deleting is the only thing that keeps them the size of the window instead of the size of all traffic ever
 * accepted. The partition job next door handles the tables that are partitioned (DB-02).
 *
 * <p>Deletes in bounded batches and repeats until a pass frees nothing: an unbounded {@code DELETE} on a table
 * that receives a write per accepted message is a lock held for as long as the delete runs.
 */
@Component
public class RetentionSweepScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(RetentionSweepScheduler.class);

    /** Rows per statement; small enough that the lock is never noticeable on the accept path. */
    private static final int BATCH_SIZE = 5_000;

    /** Batches per pass; the rest waits for the next tick rather than running for an unbounded time. */
    private static final int MAX_BATCHES = 20;

    private final DedupRegistryPort dedupRegistry;
    private final FrequencyCounterPort frequencyCounters;
    private final ComplianceProperties compliance;
    private final ClockPort clock;

    public RetentionSweepScheduler(
            DedupRegistryPort dedupRegistry,
            FrequencyCounterPort frequencyCounters,
            ComplianceProperties compliance,
            ClockPort clock) {
        this.dedupRegistry = dedupRegistry;
        this.frequencyCounters = frequencyCounters;
        this.compliance = compliance;
        this.clock = clock;
    }

    @Scheduled(cron = "${commhub.persistence.retention-sweep-cron:0 20 * * * *}")
    public void sweep() {
        long dedup = purge(batch -> dedupRegistry.purgeExpired(clock.now(), batch));
        long counters =
                purge(batch -> frequencyCounters.purgeBefore(clock.now().minus(compliance.counterRetention()), batch));
        if (dedup > 0 || counters > 0) {
            LOG.info(
                    "Retention sweep removed {} expired dedup keys (FR-1.5) and {} frequency counters (FR-5.4)",
                    dedup,
                    counters);
        }
    }

    /** Runs the batched delete until a pass frees nothing or the per-tick budget is used up. */
    private static long purge(BatchPurge purge) {
        long total = 0;
        for (int pass = 0; pass < MAX_BATCHES; pass++) {
            long removed = purge.apply(BATCH_SIZE);
            total += removed;
            if (removed < BATCH_SIZE) {
                break;
            }
        }
        return total;
    }

    /** One batched delete; each call opens its own transaction inside the adapter. */
    private interface BatchPurge {
        long apply(int limit);
    }
}
