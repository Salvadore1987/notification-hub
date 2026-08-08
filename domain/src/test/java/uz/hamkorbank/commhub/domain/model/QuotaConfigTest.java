package uz.hamkorbank.commhub.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static uz.hamkorbank.commhub.domain.DomainFixtures.uzs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.hamkorbank.commhub.domain.exception.DomainValidationException;
import uz.hamkorbank.commhub.domain.model.type.QuotaExhaustionBehavior;
import uz.hamkorbank.commhub.domain.model.type.QuotaVerdict;

/** Count and cost quotas with their exhaustion behaviour (FR-2.6). */
class QuotaConfigTest {

    @Test
    @DisplayName("an unlimited configuration always allows sending")
    void unlimitedAlwaysAllows() {
        // Arrange
        QuotaConfig quota = QuotaConfig.unlimited();

        // Act
        QuotaVerdict verdict = quota.evaluate(QuotaConfig.Usage.of(1_000_000L), QuotaConfig.Usage.none(), 1L, uzs("1"));

        // Assert
        assertThat(quota.isUnlimited()).isTrue();
        assertThat(verdict).isEqualTo(QuotaVerdict.ALLOWED);
        assertThat(verdict.permitsSending()).isTrue();
        assertThat(verdict.requiresAlert()).isFalse();
    }

    @Test
    @DisplayName("FR-2.6: a breached daily count blocks when configured to block")
    void breachedCountBlocks() {
        // Arrange
        QuotaConfig quota = QuotaConfig.ofCounts(100L, 1_000L, QuotaExhaustionBehavior.BLOCK_AND_ALERT);

        // Act + Assert
        assertThat(quota.evaluate(QuotaConfig.Usage.of(99L), QuotaConfig.Usage.none(), 1L, null))
                .isEqualTo(QuotaVerdict.ALLOWED);
        assertThat(quota.evaluate(QuotaConfig.Usage.of(100L), QuotaConfig.Usage.none(), 1L, null))
                .isEqualTo(QuotaVerdict.BLOCKED);
        assertThat(quota.dailyCountLimit()).contains(100L);
        assertThat(quota.monthlyCountLimit()).contains(1_000L);
    }

    @Test
    @DisplayName("FR-2.6: with ALERT_ONLY a breach only raises an alert")
    void breachedCountOnlyAlerts() {
        // Arrange
        QuotaConfig quota = QuotaConfig.ofCounts(10L, null, QuotaExhaustionBehavior.ALERT_ONLY);

        // Act
        QuotaVerdict verdict = quota.evaluate(QuotaConfig.Usage.of(10L), QuotaConfig.Usage.none(), 1L, null);

        // Assert
        assertThat(verdict).isEqualTo(QuotaVerdict.ALERT);
        assertThat(verdict.permitsSending()).isTrue();
        assertThat(verdict.requiresAlert()).isTrue();
    }

    @Test
    @DisplayName("the monthly counter is evaluated as well")
    void monthlyCounterIsEvaluated() {
        // Arrange
        QuotaConfig quota = QuotaConfig.ofCounts(null, 50L, QuotaExhaustionBehavior.BLOCK_AND_ALERT);

        // Act + Assert
        assertThat(quota.evaluate(QuotaConfig.Usage.none(), QuotaConfig.Usage.of(50L), 1L, null))
                .isEqualTo(QuotaVerdict.BLOCKED);
    }

    @Test
    @DisplayName("FR-2.6: cost budgets are evaluated against the expected send cost")
    void costBudgetIsEvaluated() {
        // Arrange
        QuotaConfig quota =
                new QuotaConfig(null, null, uzs("1000"), uzs("10000"), QuotaExhaustionBehavior.BLOCK_AND_ALERT);

        // Act + Assert
        assertThat(quota.evaluate(new QuotaConfig.Usage(0L, uzs("900")), QuotaConfig.Usage.none(), 1L, uzs("100")))
                .isEqualTo(QuotaVerdict.ALLOWED);
        assertThat(quota.evaluate(new QuotaConfig.Usage(0L, uzs("950")), QuotaConfig.Usage.none(), 1L, uzs("100")))
                .isEqualTo(QuotaVerdict.BLOCKED);
        assertThat(quota.evaluate(QuotaConfig.Usage.none(), QuotaConfig.Usage.none(), 1L, uzs("1001")))
                .isEqualTo(QuotaVerdict.BLOCKED);
        assertThat(quota.isUnlimited()).isFalse();
    }

    @Test
    @DisplayName("invalid quota configuration and usage are rejected")
    void rejectsInvalidValues() {
        // Act + Assert
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> QuotaConfig.ofCounts(-1L, null, QuotaExhaustionBehavior.ALERT_ONLY));
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> QuotaConfig.ofCounts(1L, -1L, QuotaExhaustionBehavior.ALERT_ONLY));
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> new QuotaConfig(null, null, null, null, null));
        assertThatExceptionOfType(DomainValidationException.class).isThrownBy(() -> new QuotaConfig.Usage(-1L, null));
        assertThatExceptionOfType(DomainValidationException.class).isThrownBy(() -> QuotaConfig.unlimited()
                .evaluate(QuotaConfig.Usage.none(), QuotaConfig.Usage.none(), -1L, null));
    }
}
