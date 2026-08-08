package uz.hamkorbank.commhub.domain.support;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import uz.hamkorbank.commhub.domain.exception.DomainValidationException;

/**
 * Invariant checks shared by domain value objects and aggregates.
 *
 * <p>Every failure is reported as {@link DomainValidationException} so that the application layer
 * has a single error class to map onto {@code VALIDATION_FAILED} (FR-1.4, IR-01).
 */
public final class Guard {

    private Guard() {}

    public static <T> T notNull(T value, String field) {
        if (value == null) {
            throw new DomainValidationException(field + " must not be null");
        }
        return value;
    }

    public static String notBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new DomainValidationException(field + " must not be blank");
        }
        return value;
    }

    public static String maxLength(String value, int max, String field) {
        if (value != null && value.length() > max) {
            throw new DomainValidationException(
                    "%s must not exceed %d characters, got %d".formatted(field, max, value.length()));
        }
        return value;
    }

    public static String matches(String value, Pattern pattern, String field) {
        notBlank(value, field);
        if (!pattern.matcher(value).matches()) {
            throw new DomainValidationException(
                    "%s has an invalid format: expected %s".formatted(field, pattern.pattern()));
        }
        return value;
    }

    public static int notNegative(int value, String field) {
        if (value < 0) {
            throw new DomainValidationException(field + " must not be negative");
        }
        return value;
    }

    public static long notNegative(long value, String field) {
        if (value < 0L) {
            throw new DomainValidationException(field + " must not be negative");
        }
        return value;
    }

    public static int positive(int value, String field) {
        if (value <= 0) {
            throw new DomainValidationException(field + " must be positive");
        }
        return value;
    }

    public static void isTrue(boolean condition, String message) {
        if (!condition) {
            throw new DomainValidationException(message);
        }
    }

    /** Returns an immutable copy; {@code null} is treated as an empty list. */
    public static <T> List<T> copyOf(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    /** Returns an immutable copy; {@code null} is treated as an empty set. */
    public static <T> Set<T> copyOf(Set<T> values) {
        return values == null ? Set.of() : Set.copyOf(values);
    }

    /** Returns an immutable copy; {@code null} is treated as an empty map. */
    public static <K, V> Map<K, V> copyOf(Map<K, V> values) {
        return values == null ? Map.of() : Map.copyOf(values);
    }
}
