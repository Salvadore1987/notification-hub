package uz.hamkorbank.commhub.adapter.out.persistence.support;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.UUID;
import uz.hamkorbank.commhub.domain.model.vo.Money;

/**
 * Null-safe readers for the column types this schema uses.
 *
 * <p>JDBC returns {@code 0} for a null numeric and needs {@link ResultSet#wasNull()} to tell that
 * apart from a stored zero; these helpers make the distinction once instead of in every row mapper.
 */
public final class SqlValues {

    private SqlValues() {}

    public static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime timestamp = rs.getObject(column, OffsetDateTime.class);
        return timestamp == null ? null : timestamp.toInstant();
    }

    /**
     * Binds an instant to a {@code timestamptz} parameter.
     *
     * <p>The driver has no mapping for {@link Instant}, so it is offered as an {@link OffsetDateTime}
     * at UTC — the storage timezone of the whole system (UI-04).
     */
    public static OffsetDateTime timestamp(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    public static UUID uuid(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, UUID.class);
    }

    public static <E extends Enum<E>> E enumValue(ResultSet rs, String column, Class<E> type) throws SQLException {
        String value = rs.getString(column);
        return value == null ? null : Enum.valueOf(type, value);
    }

    public static Long longValue(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    public static Integer intValue(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    /** Reads an amount/currency column pair; both are null together (CHECK-констрейнты §10.1). */
    public static Money money(ResultSet rs, String amountColumn, String currencyColumn) throws SQLException {
        BigDecimal amount = rs.getBigDecimal(amountColumn);
        String currency = rs.getString(currencyColumn);
        if (amount == null || currency == null) {
            return null;
        }
        return Money.of(amount, Currency.getInstance(currency.trim()));
    }

    /** Amount of a {@link Money} for its numeric column; {@code null} when the money is absent. */
    public static BigDecimal amountOf(Money money) {
        return money == null ? null : money.amount();
    }

    /** Currency code of a {@link Money} for its {@code char(3)} column. */
    public static String currencyOf(Money money) {
        return money == null ? null : money.currency().getCurrencyCode();
    }

    /** Name of an enum constant for its text column; {@code null} stays {@code null}. */
    public static String nameOf(Enum<?> value) {
        return value == null ? null : value.name();
    }
}
