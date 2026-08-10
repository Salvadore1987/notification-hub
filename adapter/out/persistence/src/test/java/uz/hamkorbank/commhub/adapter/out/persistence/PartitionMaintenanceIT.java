package uz.hamkorbank.commhub.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Partition maintenance is the part of DB-02 that has to keep working unattended: a month without a
 * partition would be a month of rejected inserts, and a retention job that cannot detach would be a
 * table that only ever grows (DB-03).
 */
class PartitionMaintenanceIT extends AbstractPersistenceIT {

    PartitionMaintenanceIT(JdbcClient jdbcClient, TransactionTemplate transactionTemplate) {
        super(jdbcClient, transactionTemplate);
    }

    @Test
    @DisplayName("DB-02: every time-partitioned table has a default partition as a safety net")
    void everyPartitionedTableHasDefaultPartition() {
        // Arrange
        List<String> expected = List.of(
                "message_default",
                "message_status_history_default",
                "delivery_attempt_default",
                "outbox_event_default");

        // Act
        List<String> defaults = jdbc().sql("""
                        SELECT child.relname
                        FROM pg_inherits
                        JOIN pg_class child ON child.oid = pg_inherits.inhrelid
                        WHERE pg_get_expr(child.relpartbound, child.oid) = 'DEFAULT'
                        """).query(String.class).list();

        // Assert
        assertThat(defaults).containsAll(expected);
    }

    @Test
    @DisplayName("DB-02: ensure_partitions is idempotent and creates the months ahead")
    void ensurePartitionsIsIdempotent() {
        // Arrange
        OffsetDateTime reference = OffsetDateTime.of(2027, 3, 15, 0, 0, 0, 0, ZoneOffset.UTC);

        // Act
        List<String> created = ensurePartitions(reference, 1);
        List<String> repeated = ensurePartitions(reference, 1);

        // Assert
        assertThat(created).contains("message_y2027m03", "message_y2027m04", "outbox_event_y2027m03");
        assertThat(repeated).isEqualTo(created);
        assertThat(partitionsOf("message")).contains("message_y2027m03", "message_y2027m04");
    }

    @Test
    @DisplayName("DB-03: detach_partitions_before releases only the months that are entirely older")
    void detachesOldPartitionsOnly() {
        // Arrange
        ensurePartitions(OffsetDateTime.of(2027, 6, 1, 0, 0, 0, 0, ZoneOffset.UTC), 2);

        // Act
        List<String> detached = jdbc().sql("SELECT comm_hub.detach_partitions_before('message', DATE '2027-07-01')")
                .query(String.class)
                .list();

        // Assert
        assertThat(detached).contains("message_y2027m06").doesNotContain("message_y2027m07");
        assertThat(partitionsOf("message")).doesNotContain("message_y2027m06").contains("message_y2027m07");
    }

    private List<String> ensurePartitions(OffsetDateTime reference, int monthsAhead) {
        return jdbc().sql("SELECT comm_hub.ensure_partitions(:monthsAhead, :now)")
                .param("monthsAhead", monthsAhead)
                .param("now", reference)
                .query(String.class)
                .list();
    }

    private List<String> partitionsOf(String table) {
        return jdbc().sql("""
                        SELECT child.relname
                        FROM pg_inherits
                        JOIN pg_class parent ON parent.oid = pg_inherits.inhparent
                        JOIN pg_class child ON child.oid = pg_inherits.inhrelid
                        WHERE parent.relname = :table
                        """).param("table", table).query(String.class).list();
    }
}
