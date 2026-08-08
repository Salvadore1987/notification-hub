package uz.hamkorbank.commhub.domain.model.vo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Monetary amount used for provider tariffs, message cost and budgets (FR-2.1, FR-2.6, FR-6.2).
 *
 * <p>Scaled to four fraction digits so that a per-segment tariff of, say, 24.5 UZS stays exact and
 * value equality behaves predictably.
 */
public record Money(BigDecimal amount, Currency currency) implements Comparable<Money> {

    public static final int SCALE = 4;

    public Money {
        Guard.notNull(amount, "Money.amount");
        Guard.notNull(currency, "Money.currency");
        Guard.isTrue(amount.signum() >= 0, "Money.amount must not be negative");
        amount = amount.setScale(SCALE, RoundingMode.HALF_UP);
    }

    public static Money of(BigDecimal amount, Currency currency) {
        return new Money(amount, currency);
    }

    public static Money of(String amount, String currencyCode) {
        Guard.notBlank(amount, "Money.amount");
        Guard.notBlank(currencyCode, "Money.currency");
        return new Money(new BigDecimal(amount), Currency.getInstance(currencyCode));
    }

    public static Money zero(Currency currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public Money multipliedBy(long factor) {
        Guard.notNegative(factor, "factor");
        return new Money(amount.multiply(BigDecimal.valueOf(factor)), currency);
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }

    public boolean isGreaterThan(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount) > 0;
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount);
    }

    @Override
    public String toString() {
        return amount.toPlainString() + " " + currency.getCurrencyCode();
    }

    private void requireSameCurrency(Money other) {
        Guard.notNull(other, "other");
        Guard.isTrue(
                currency.equals(other.currency),
                "currency mismatch: %s vs %s".formatted(currency.getCurrencyCode(), other.currency.getCurrencyCode()));
    }
}
