package uz.hamkorbank.commhub.adapter.out.persistence.json;

import java.math.BigDecimal;
import java.util.Currency;
import uz.hamkorbank.commhub.domain.model.vo.Money;

/**
 * {@link Money} inside a {@code jsonb} column.
 *
 * <p>The amount is a string: JSON numbers are doubles for most parsers, and a tariff read back as
 * {@code 0.30000000000000004} would corrupt every cost report built on it (FR-6.2).
 */
public record MoneyJson(String amount, String currency) {

    public static MoneyJson of(Money money) {
        return money == null
                ? null
                : new MoneyJson(money.amount().toPlainString(), money.currency().getCurrencyCode());
    }

    public Money toDomain() {
        return Money.of(new BigDecimal(amount), Currency.getInstance(currency));
    }

    public static Money toDomain(MoneyJson json) {
        return json == null ? null : json.toDomain();
    }
}
