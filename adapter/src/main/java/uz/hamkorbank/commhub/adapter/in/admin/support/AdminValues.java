package uz.hamkorbank.commhub.adapter.in.admin.support;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import uz.hamkorbank.commhub.adapter.in.contract.InboundContractException;
import uz.hamkorbank.commhub.domain.model.vo.Money;

/**
 * Turns the strings of an admin request into the values the use cases take (IR-01).
 *
 * <p>Every failure here is an {@link InboundContractException} naming the field, which the existing
 * advice renders as {@code problem+json} — the same answer a source system gets for the same class of
 * mistake. Letting a {@code Guard} deep in the application throw instead would produce a 500 for a
 * mistyped dropdown value, and a 500 is what an operator reports as an outage.
 *
 * <p>Nothing here is lenient. An unknown enum constant is refused rather than defaulted, because the
 * defaults available would be {@code null} — which several of these fields read as "leave unchanged" —
 * and a typo that silently means "leave unchanged" is a configuration edit that appears to have worked.
 */
public final class AdminValues {

    /** Currency of the Bank's tariffs; amounts in a request carry no currency of their own. */
    private static final String DEFAULT_CURRENCY = "UZS";

    private AdminValues() {}

    /** Required enum constant, matched case-insensitively because a URL is not a Java identifier. */
    public static <E extends Enum<E>> E requiredEnum(Class<E> type, String value, String field) {
        E parsed = optionalEnum(type, value, field);
        if (parsed == null) {
            throw InboundContractException.missing(field);
        }
        return parsed;
    }

    /** Optional enum constant; a blank or absent value means "any" or "unchanged", per the caller. */
    public static <E extends Enum<E>> E optionalEnum(Class<E> type, String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw InboundContractException.invalid(field, "expected one of " + names(type), e);
        }
    }

    public static UUID requiredUuid(String value, String field) {
        if (value == null || value.isBlank()) {
            throw InboundContractException.missing(field);
        }
        return uuid(value, field);
    }

    public static UUID uuid(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            throw InboundContractException.invalid(field, "is not a UUID", e);
        }
    }

    public static Instant instant(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.trim());
        } catch (RuntimeException e) {
            throw InboundContractException.invalid(field, "is not an ISO-8601 instant", e);
        }
    }

    public static LocalTime localTime(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalTime.parse(value.trim());
        } catch (RuntimeException e) {
            throw InboundContractException.invalid(field, "is not a local time (HH:mm)", e);
        }
    }

    public static ZoneId zone(String value, String field, ZoneId fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return ZoneId.of(value.trim());
        } catch (RuntimeException e) {
            throw InboundContractException.invalid(field, "is not an IANA time zone", e);
        }
    }

    /**
     * An amount in the Bank's currency.
     *
     * <p>The request carries a number and not a currency: a tariff in a currency the rest of the system
     * does not price in would make every total meaningless, and the place to add a second currency is
     * the tariff model, not a free-text field on a form.
     */
    public static Money money(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Money.of(new BigDecimal(value.trim()), java.util.Currency.getInstance(DEFAULT_CURRENCY));
        } catch (RuntimeException e) {
            throw InboundContractException.invalid(field, "is not a decimal amount", e);
        }
    }

    /** Applies a value object factory, turning its validation failure into a field-pointed refusal. */
    public static <T> T parse(String value, String field, java.util.function.Function<String, T> factory) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return factory.apply(value.trim());
        } catch (RuntimeException e) {
            throw InboundContractException.invalid(field, e.getMessage(), e);
        }
    }

    /** Same, for a value that has to be there. */
    public static <T> T parseRequired(String value, String field, java.util.function.Function<String, T> factory) {
        if (value == null || value.isBlank()) {
            throw InboundContractException.missing(field);
        }
        return parse(value, field, factory);
    }

    public static List<String> list(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static <E extends Enum<E>> String names(Class<E> type) {
        return String.join(
                ", ",
                java.util.Arrays.stream(type.getEnumConstants()).map(Enum::name).toList());
    }
}
