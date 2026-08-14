package uz.hamkorbank.commhub.adapter.in.admin.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.hamkorbank.commhub.adapter.in.admin.dto.QuotaDto;
import uz.hamkorbank.commhub.adapter.in.contract.InboundContractException;
import uz.hamkorbank.commhub.domain.model.QuotaConfig;
import uz.hamkorbank.commhub.domain.model.type.QuotaExhaustionBehavior;

/**
 * Quota mapping of the admin BFF (FR-2.6).
 *
 * <p>The behaviour of an exhausted quota decides whether a customer's message goes out, so the mapper
 * is not allowed to pick it. It used to: an absent value became {@code ALERT_ONLY}, and a quota created
 * from the panel with only a ceiling filled in counted, alerted and stopped nothing on every ingress
 * (D-11). These tests pin the rule the fix installed.
 */
class AdminCommandMapperTest {

    private final AdminCommandMapper mapper = new AdminCommandMapperImpl();

    @Test
    @DisplayName("квота со счётным потолком без поведения — отказ с указателем на поле")
    void refusesACountCeilingWithoutBehaviour() {
        QuotaDto quota = new QuotaDto(3L, null, null, null, null);

        assertThatThrownBy(() -> mapper.toQuota(quota))
                .isInstanceOf(InboundContractException.class)
                .hasMessageContaining("quota.behavior")
                .extracting(exception -> ((InboundContractException) exception).field())
                .isEqualTo("quota.behavior");
    }

    @Test
    @DisplayName("стоимостный потолок требует поведения так же, как счётный")
    void refusesACostCeilingWithoutBehaviour() {
        QuotaDto quota = new QuotaDto(null, null, "1000000.00 UZS", null, "  ");

        assertThatThrownBy(() -> mapper.toQuota(quota)).isInstanceOf(InboundContractException.class);
    }

    @Test
    @DisplayName("заданное поведение доезжает до домена без подмены")
    void keepsTheBehaviourItWasGiven() {
        QuotaDto quota = new QuotaDto(3L, null, null, null, "BLOCK_AND_ALERT");

        QuotaConfig config = mapper.toQuota(quota);

        assertThat(config.behavior()).isEqualTo(QuotaExhaustionBehavior.BLOCK_AND_ALERT);
        assertThat(config.dailyCount()).isEqualTo(3L);
    }

    @Test
    @DisplayName("квота без единого потолка поведения не требует: блокировать нечего")
    void allowsAQuotaWithoutAnyCeiling() {
        QuotaConfig config = mapper.toQuota(new QuotaDto(null, null, null, null, null));

        assertThat(config.isUnlimited()).isTrue();
        assertThat(config.behavior()).isEqualTo(QuotaExhaustionBehavior.ALERT_ONLY);
    }

    @Test
    @DisplayName("квоты нет вовсе — null, а не пустая квота")
    void keepsAnAbsentQuotaAbsent() {
        assertThat(mapper.toQuota(null)).isNull();
    }
}
