package uz.hamkorbank.commhub.domain.model.vo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.math.BigDecimal;
import java.util.Currency;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.hamkorbank.commhub.domain.exception.DomainValidationException;

/** Money used by tariffs, message cost and budgets (FR-2.1, FR-2.6, FR-6.2). */
class MoneyTest {

    private static final Currency UZS = Currency.getInstance("UZS");

    @Test
    @DisplayName("amounts are scaled so that equality is predictable")
    void normalisesScale() {
        // Act
        Money oneWay = Money.of("25", "UZS");
        Money otherWay = Money.of(new BigDecimal("25.0000"), UZS);

        // Assert
        assertThat(oneWay).isEqualTo(otherWay);
        assertThat(oneWay.amount().scale()).isEqualTo(Money.SCALE);
        assertThat(oneWay).hasToString("25.0000 UZS");
    }

    @Test
    @DisplayName("arithmetic keeps the currency and rejects negatives")
    void supportsArithmetic() {
        // Arrange
        Money perSegment = Money.of("24.5", "UZS");

        // Act
        Money threeSegments = perSegment.multipliedBy(3L);
        Money total = threeSegments.plus(Money.of("0.5", "UZS"));

        // Assert
        assertThat(threeSegments).isEqualTo(Money.of("73.5", "UZS"));
        assertThat(total).isEqualTo(Money.of("74", "UZS"));
        assertThat(Money.zero(UZS).isZero()).isTrue();
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> Money.of("-1", "UZS"))
                .withMessageContaining("must not be negative");
    }

    @Test
    @DisplayName("comparison and addition across currencies fail loudly")
    void refusesMixedCurrencies() {
        // Arrange
        Money uzs = Money.of("100", "UZS");
        Money usd = Money.of("100", "USD");

        // Act + Assert
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> uzs.plus(usd))
                .withMessageContaining("currency mismatch");
        assertThatExceptionOfType(DomainValidationException.class).isThrownBy(() -> uzs.compareTo(usd));
    }

    @Test
    @DisplayName("ordering follows the amount")
    void ordersByAmount() {
        // Act + Assert
        assertThat(Money.of("30", "UZS").isGreaterThan(Money.of("25", "UZS"))).isTrue();
        assertThat(Money.of("25", "UZS").compareTo(Money.of("30", "UZS"))).isNegative();
        assertThat(Money.of("25", "UZS").multipliedBy(0L).isZero()).isTrue();
    }
}
