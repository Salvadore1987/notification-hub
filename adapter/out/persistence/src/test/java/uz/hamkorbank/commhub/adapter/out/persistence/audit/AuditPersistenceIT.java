package uz.hamkorbank.commhub.adapter.out.persistence.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import uz.hamkorbank.commhub.adapter.out.persistence.AbstractPersistenceIT;
import uz.hamkorbank.commhub.application.port.in.query.AuditQuery;
import uz.hamkorbank.commhub.application.port.out.AuditEntry;
import uz.hamkorbank.commhub.domain.model.Actor;
import uz.hamkorbank.commhub.domain.support.UuidV7;

/**
 * The audit trail accepts the rendered before/after states the use cases produce (FR-7.3, SEC-08).
 *
 * <p>The columns are {@code jsonb} while the port carries rendered text, so this is the test that keeps the
 * two in step: an audit write that throws would roll back the very change it was journalling.
 */
class AuditPersistenceIT extends AbstractPersistenceIT {

    private static final Instant OCCURRED_AT = Instant.parse("2026-08-08T09:00:00Z");

    private final AuditPersistenceAdapter audit;

    AuditPersistenceIT(JdbcClient jdbcClient, TransactionTemplate transactionTemplate, AuditPersistenceAdapter audit) {
        super(jdbcClient, transactionTemplate);
        this.audit = audit;
    }

    /**
     * Nothing is cleared between tests, and that is not an oversight.
     *
     * <p>{@code audit_log} refuses {@code UPDATE}, {@code DELETE} and {@code TRUNCATE} in the database
     * itself (V7): an audit journal a test can empty is one an administrator can empty. The tests are
     * therefore written to be indifferent to what is already there — every one of them writes under an
     * entity id of its own and asserts through a filter, exactly the way the journal is read (FR-7.3).
     */
    private final String entityId = "IT-" + UuidV7.generate();

    @Test
    @DisplayName("a configuration change is journalled with its rendered before and after states (FR-7.3)")
    void writesRenderedStates() {
        // Arrange — exactly what ConfigAuditor renders: text, not JSON
        AuditEntry entry = new AuditEntry(
                Actor.operator("ivanov"),
                "channel.state",
                "channel",
                entityId,
                "status=ACTIVE, strategy=WEIGHTED, order=[PLAYMOBILE, SMSGATE]",
                "status=MAINTENANCE, strategy=WEIGHTED, order=[PLAYMOBILE, SMSGATE], reason=плановые работы",
                "10.1.2.3",
                OCCURRED_AT);

        // Act
        audit.write(entry);

        // Assert
        assertThat(jdbc().sql("SELECT before_state #>> '{}' FROM audit_log WHERE entity_id = :id")
                        .param("id", entityId)
                        .query(String.class)
                        .single())
                .isEqualTo("status=ACTIVE, strategy=WEIGHTED, order=[PLAYMOBILE, SMSGATE]");
        assertThat(jdbc().sql("SELECT after_state #>> '{}' FROM audit_log WHERE entity_id = :id")
                        .param("id", entityId)
                        .query(String.class)
                        .single())
                .contains("плановые работы");
        // host(ip), not ip::text: the column is inet and the driver renders it with its netmask
        // ("10.1.2.3/32"), while what the journal is read for is the address (FR-7.3).
        assertThat(jdbc().sql("SELECT username, entity_id, host(ip) FROM audit_log WHERE entity_id = :id")
                        .param("id", entityId)
                        .query((rs, rowNum) -> rs.getString(1) + "/" + rs.getString(2) + "/" + rs.getString(3))
                        .single())
                .isEqualTo("ivanov/" + entityId + "/10.1.2.3");
    }

    @Test
    @DisplayName("a creation and a deletion leave the missing side null rather than the string \"null\"")
    void keepsMissingStatesNull() {
        // Arrange
        audit.write(new AuditEntry(
                Actor.operator("petrov"),
                "template.create",
                "template",
                entityId,
                null,
                "status=ACTIVE",
                null,
                OCCURRED_AT));

        // Act + Assert
        assertThat(jdbc().sql("SELECT count(*) FROM audit_log"
                                + " WHERE entity_id = :id AND before_state IS NULL AND after_state IS NOT NULL")
                        .param("id", entityId)
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("an actor the user table does not know is still journalled, by type (FR-7.3)")
    void journalsUnknownActor() {
        // Arrange
        audit.write(AuditEntry.of(Actor.system(), "template.import", "template", entityId, OCCURRED_AT));

        // Act + Assert
        assertThat(jdbc().sql("SELECT username FROM audit_log WHERE entity_id = :id")
                        .param("id", entityId)
                        .query(String.class)
                        .single())
                .isEqualTo("SYSTEM");
        assertThat(jdbc().sql("SELECT count(*) FROM audit_log WHERE entity_id = :id AND user_id IS NULL")
                        .param("id", entityId)
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("FR-7.3: the journal is searchable by entity, by user and by period")
    void searchesTheJournal() {
        // Arrange
        audit.write(new AuditEntry(
                Actor.operator("ivanov"),
                "provider.disabled",
                "provider",
                entityId,
                "enabled=true",
                "enabled=false",
                "10.1.2.3",
                OCCURRED_AT));
        audit.write(AuditEntry.of(Actor.operator("petrov"), "template.published", "template", entityId, OCCURRED_AT));

        // Act
        List<AuditEntry> byEntity = audit.search(AuditQuery.ofEntity("provider", entityId));
        List<AuditEntry> byUser = audit.search(new AuditQuery(null, null, "petrov", null, null, entityId, 50, 0));
        List<AuditEntry> beforeTheAction =
                audit.search(new AuditQuery(null, OCCURRED_AT.minusSeconds(1), null, null, null, entityId, 50, 0));

        // Assert
        assertThat(byEntity).singleElement().satisfies(entry -> {
            assertThat(entry.action()).isEqualTo("provider.disabled");
            assertThat(entry.before()).isEqualTo("enabled=true");
            assertThat(entry.sourceIp()).isEqualTo("10.1.2.3");
            assertThat(entry.actor().id()).isEqualTo("ivanov");
        });
        assertThat(byUser).singleElement().extracting(AuditEntry::action).isEqualTo("template.published");
        assertThat(beforeTheAction).isEmpty();
        assertThat(audit.count(new AuditQuery(null, null, null, null, null, entityId, 50, 0)))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("a page is bounded and ordered newest first (UI-03)")
    void pagesNewestFirst() {
        // Arrange
        audit.write(AuditEntry.of(Actor.operator("ivanov"), "first", "batch", entityId, OCCURRED_AT));
        audit.write(AuditEntry.of(Actor.operator("ivanov"), "second", "batch", entityId, OCCURRED_AT.plusSeconds(60)));

        // Act
        List<AuditEntry> page = audit.search(new AuditQuery(null, null, null, null, null, entityId, 1, 0));
        List<AuditEntry> next = audit.search(new AuditQuery(null, null, null, null, null, entityId, 1, 1));

        // Assert
        assertThat(page).singleElement().extracting(AuditEntry::action).isEqualTo("second");
        assertThat(next).singleElement().extracting(AuditEntry::action).isEqualTo("first");
    }
}
