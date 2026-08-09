package uz.hamkorbank.commhub.adapter.out.persistence.support;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uz.hamkorbank.commhub.application.port.out.ClockPort;

/**
 * Keeps the time-partitioned tables ready to be written to and, when operations allows it, releases the
 * months that fell out of the retention window (DB-02, DB-03).
 *
 * <p>Runs on every instance; that is intentional and safe — creating a partition is idempotent, and the
 * loser of a race gets a partition that already exists rather than an error. Skipping the job on
 * standby instances would mean a single node's downtime silently stops partition creation.
 *
 * <p>Detaching is off by default: an unpaired detach would leave data outside the schema with no archive
 * behind it, so the switch is turned on together with the archiving procedure of DB-03.
 */
@Component
public class PartitionMaintenanceJob {

    private static final Logger LOG = LoggerFactory.getLogger(PartitionMaintenanceJob.class);

    private static final List<String> PARTITIONED_TABLES =
            List.of("message", "message_status_history", "delivery_attempt", "outbox_event");

    private final JdbcClient jdbcClient;
    private final ClockPort clock;
    private final PersistenceProperties properties;

    public PartitionMaintenanceJob(JdbcClient jdbcClient, ClockPort clock, PersistenceProperties properties) {
        this.jdbcClient = jdbcClient;
        this.clock = clock;
        this.properties = properties;
    }

    @Scheduled(cron = "${commhub.persistence.maintenance-cron:0 30 2 * * *}")
    @Transactional
    public void maintainPartitions() {
        List<String> created = ensurePartitions();
        if (!created.isEmpty()) {
            LOG.info("Partition maintenance prepared {} partitions: {}", created.size(), created);
        }
        if (properties.detachOldPartitions()) {
            detachExpiredPartitions();
        }
    }

    /** Creates the partitions of the current month and of the configured months ahead (DB-02). */
    public List<String> ensurePartitions() {
        return jdbcClient
                .sql("SELECT comm_hub.ensure_partitions(:monthsAhead, :now)")
                .param("monthsAhead", properties.partitionsAhead())
                .param("now", OffsetDateTime.ofInstant(clock.now(), ZoneOffset.UTC))
                .query(String.class)
                .list();
    }

    /** Detaches partitions entirely older than the retention window; the data is not deleted (DB-03). */
    public List<String> detachExpiredPartitions() {
        LocalDate cutoff = clock.now().atZone(ZoneOffset.UTC).toLocalDate().minusMonths(properties.retentionMonths());
        List<String> detached = PARTITIONED_TABLES.stream()
                .flatMap(table -> detach(table, cutoff).stream())
                .toList();
        if (!detached.isEmpty()) {
            LOG.warn(
                    "Retention (DB-03): detached {} partitions older than {}; they must be archived: {}",
                    detached.size(),
                    cutoff,
                    detached);
        }
        return detached;
    }

    private List<String> detach(String table, LocalDate cutoff) {
        return jdbcClient
                .sql("SELECT comm_hub.detach_partitions_before(:table, :cutoff)")
                .param("table", table)
                .param("cutoff", cutoff)
                .query(String.class)
                .list();
    }
}
