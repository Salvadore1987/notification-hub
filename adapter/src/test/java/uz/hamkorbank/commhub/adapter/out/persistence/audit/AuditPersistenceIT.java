package uz.hamkorbank.commhub.adapter.out.persistence.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import uz.hamkorbank.commhub.adapter.out.persistence.AbstractPersistenceIT;
import uz.hamkorbank.commhub.application.port.out.AuditEntry;
import uz.hamkorbank.commhub.domain.model.Actor;

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

    @BeforeEach
    void clearAudit() {
        truncate("audit_log");
    }

    @Test
    @DisplayName("a configuration change is journalled with its rendered before and after states (FR-7.3)")
    void writesRenderedStates() {
        // Arrange — exactly what ConfigAuditor renders: text, not JSON
        AuditEntry entry = new AuditEntry(
                Actor.operator("ivanov"),
                "channel.state",
                "channel",
                "SMS",
                "status=ACTIVE, strategy=WEIGHTED, order=[PLAYMOBILE, SMSGATE]",
                "status=MAINTENANCE, strategy=WEIGHTED, order=[PLAYMOBILE, SMSGATE], reason=плановые работы",
                "10.1.2.3",
                OCCURRED_AT);

        // Act
        audit.write(entry);

        // Assert
        assertThat(jdbc().sql("SELECT before_state #>> '{}' FROM audit_log WHERE action = 'channel.state'")
                        .query(String.class)
                        .single())
                .isEqualTo("status=ACTIVE, strategy=WEIGHTED, order=[PLAYMOBILE, SMSGATE]");
        assertThat(jdbc().sql("SELECT after_state #>> '{}' FROM audit_log WHERE action = 'channel.state'")
                        .query(String.class)
                        .single())
                .contains("плановые работы");
        assertThat(jdbc().sql("SELECT username, entity_id, ip::text FROM audit_log")
                        .query((rs, rowNum) -> rs.getString(1) + "/" + rs.getString(2) + "/" + rs.getString(3))
                        .single())
                .isEqualTo("ivanov/SMS/10.1.2.3");
    }

    @Test
    @DisplayName("a creation and a deletion leave the missing side null rather than the string \"null\"")
    void keepsMissingStatesNull() {
        // Arrange
        audit.write(new AuditEntry(
                Actor.operator("petrov"),
                "template.create",
                "template",
                "OTP_LOGIN",
                null,
                "status=ACTIVE",
                null,
                OCCURRED_AT));

        // Act + Assert
        assertThat(jdbc().sql("SELECT count(*) FROM audit_log WHERE before_state IS NULL AND after_state IS NOT NULL")
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("an actor the user table does not know is still journalled, by type (FR-7.3)")
    void journalsUnknownActor() {
        // Arrange
        audit.write(AuditEntry.of(Actor.system(), "template.import", "template", "catalogue", OCCURRED_AT));

        // Act + Assert
        assertThat(jdbc().sql("SELECT username FROM audit_log WHERE action = 'template.import'")
                        .query(String.class)
                        .single())
                .isEqualTo("SYSTEM");
        assertThat(jdbc().sql("SELECT count(*) FROM audit_log WHERE user_id IS NULL")
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
    }
}
