package uz.hamkorbank.commhub.adapter.out.persistence.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import uz.hamkorbank.commhub.adapter.out.persistence.AbstractPersistenceIT;
import uz.hamkorbank.commhub.application.port.out.KillSwitchState;
import uz.hamkorbank.commhub.application.port.out.SystemParameter;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;

/**
 * The administration section of §11.2: the global kill switch (FR-3.2) and the system parameters
 * (NF-06).
 *
 * <p>Both live in the database rather than in the JVM because every instance has to see the same
 * answer within seconds (AD-07), which is exactly what these tests check: what was written is what the
 * next read returns, on a fresh adapter that shares nothing with the one that wrote it.
 */
class AdministrationPersistenceIT extends AbstractPersistenceIT {

    private static final Instant CHANGED_AT = Instant.parse("2026-08-08T03:14:00Z");

    private final KillSwitchPersistenceAdapter killSwitch;
    private final SystemParameterPersistenceAdapter parameters;

    AdministrationPersistenceIT(
            JdbcClient jdbcClient,
            TransactionTemplate transactionTemplate,
            KillSwitchPersistenceAdapter killSwitch,
            SystemParameterPersistenceAdapter parameters) {
        super(jdbcClient, transactionTemplate);
        this.killSwitch = killSwitch;
        this.parameters = parameters;
    }

    @BeforeEach
    void resetAdministration() {
        truncate("system_parameter");
        killSwitch.update(KillSwitchState.inactive());
    }

    // ---------------------------------------------------------------- kill switch

    @Test
    @DisplayName("FR-3.2: V14 seeds the switch as inactive, so 'off' and 'never touched' are one state")
    void startsInactive() {
        // Act
        KillSwitchState state = killSwitch.state();

        // Assert
        assertThat(state.active()).isFalse();
        assertThat(state.includesCriticalOtp()).isFalse();
        assertThat(state.stops(TrafficClass.NOTIFICATION)).isFalse();
    }

    @Test
    @DisplayName("FR-3.2: an activation is readable at once and holds back everything but OTP")
    void activationIsVisibleImmediately() {
        // Arrange + Act
        killSwitch.update(KillSwitchState.activated(false, CHANGED_AT, "a.karimov", "provider outage"));
        KillSwitchState state = killSwitch.state();

        // Assert — a local write invalidates the local snapshot, so no waiting for the TTL.
        assertThat(state.active()).isTrue();
        assertThat(state.changedAt()).isEqualTo(CHANGED_AT);
        assertThat(state.changedBy()).isEqualTo("a.karimov");
        assertThat(state.reason()).isEqualTo("provider outage");
        assertThat(state.stops(TrafficClass.NOTIFICATION)).isTrue();
        assertThat(state.stops(TrafficClass.TRANSACTIONAL)).isTrue();
        assertThat(state.stops(TrafficClass.CRITICAL_OTP)).isFalse();
    }

    @Test
    @DisplayName("FR-3.2: OTP is stopped only when the operator asked for it explicitly")
    void otpIsStoppedOnlyOnRequest() {
        // Arrange + Act
        killSwitch.update(KillSwitchState.activated(true, CHANGED_AT, "a.karimov", "full stop"));

        // Assert
        assertThat(killSwitch.state().stops(TrafficClass.CRITICAL_OTP)).isTrue();
    }

    @Test
    @DisplayName("AD-07: the table holds exactly one row, whatever the switch is flipped to")
    void keepsASingleRow() {
        // Arrange + Act
        killSwitch.update(KillSwitchState.activated(false, CHANGED_AT, "a.karimov", "outage"));
        killSwitch.update(KillSwitchState.deactivated(CHANGED_AT.plusSeconds(600), "a.karimov"));

        // Assert
        assertThat(jdbc().sql("SELECT count(*) FROM kill_switch")
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
        assertThat(killSwitch.state().active()).isFalse();
        assertThat(killSwitch.state().reason()).isNull();
    }

    // ---------------------------------------------------------------- system parameters

    @Test
    @DisplayName("NF-06: a parameter round-trips and a second write replaces it rather than adding a row")
    void parametersUpsert() {
        // Arrange
        parameters.save(new SystemParameter("dashboard.banner", "maintenance", "Banner", CHANGED_AT, "a.karimov"));

        // Act
        parameters.save(new SystemParameter(
                "dashboard.banner", "all clear", "Banner", CHANGED_AT.plusSeconds(60), "s.usmanov"));
        Optional<SystemParameter> stored = parameters.find("dashboard.banner");

        // Assert
        assertThat(stored).isPresent();
        assertThat(stored.get().value()).isEqualTo("all clear");
        assertThat(stored.get().updatedBy()).isEqualTo("s.usmanov");
        assertThat(parameters.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("NF-06: the list is ordered by key, which is the order the screen shows it in")
    void parametersAreOrderedByKey() {
        // Arrange
        parameters.save(new SystemParameter("z.last", "1", null, CHANGED_AT, "a.karimov"));
        parameters.save(new SystemParameter("a.first", "2", null, CHANGED_AT, "a.karimov"));

        // Act + Assert
        assertThat(parameters.findAll()).extracting(SystemParameter::key).containsExactly("a.first", "z.last");
    }

    @Test
    @DisplayName("NF-06: a removed parameter is gone and an unknown key reads as absent")
    void parametersCanBeRemoved() {
        // Arrange
        parameters.save(new SystemParameter("dashboard.banner", "hello", null, CHANGED_AT, "a.karimov"));

        // Act
        parameters.delete("dashboard.banner");

        // Assert
        assertThat(parameters.find("dashboard.banner")).isEmpty();
        assertThat(parameters.find("never.existed")).isEmpty();
        assertThat(parameters.findAll()).isEmpty();
    }
}
