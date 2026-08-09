package uz.hamkorbank.commhub.adapter.out.persistence.support;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of the storage layer that operations owns rather than code (DB-02, DB-03, DB-06).
 *
 * @param partitionsAhead how many months of partitions to keep prepared beyond the current one; two is
 *     one full month of slack before the first insert would land in the default partition
 * @param retentionMonths how long delivery history stays in the hot storage; ≥ 12 by DB-03, the exact
 *     figure follows the retention rules of the Central Bank of Uzbekistan
 * @param detachOldPartitions whether the maintenance job may detach partitions past the retention;
 *     off by default, because detaching is an operational decision that has to be paired with archiving
 * @param maintenanceCron when the maintenance job runs; nightly, outside the traffic peak
 */
@ConfigurationProperties("commhub.persistence")
public record PersistenceProperties(
        Integer partitionsAhead, Integer retentionMonths, Boolean detachOldPartitions, String maintenanceCron) {

    public static final int DEFAULT_PARTITIONS_AHEAD = 2;

    public static final int MIN_RETENTION_MONTHS = 12;

    public static final String DEFAULT_MAINTENANCE_CRON = "0 30 2 * * *";

    public PersistenceProperties {
        partitionsAhead = partitionsAhead == null ? DEFAULT_PARTITIONS_AHEAD : partitionsAhead;
        retentionMonths = retentionMonths == null ? MIN_RETENTION_MONTHS : retentionMonths;
        detachOldPartitions = detachOldPartitions != null && detachOldPartitions;
        maintenanceCron =
                maintenanceCron == null || maintenanceCron.isBlank() ? DEFAULT_MAINTENANCE_CRON : maintenanceCron;
        if (partitionsAhead < 0) {
            throw new IllegalArgumentException("commhub.persistence.partitions-ahead must not be negative");
        }
        if (retentionMonths < MIN_RETENTION_MONTHS) {
            throw new IllegalArgumentException(
                    "commhub.persistence.retention-months must be at least %d (DB-03)".formatted(MIN_RETENTION_MONTHS));
        }
    }
}
