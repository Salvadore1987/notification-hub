package uz.hamkorbank.commhub.domain.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.hamkorbank.commhub.domain.exception.DomainValidationException;

class GuardTest {

    @Test
    @DisplayName("every violation is reported as DomainValidationException with the field name")
    void violationsCarryTheFieldName() {
        // Act + Assert
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> Guard.notNull(null, "field"))
                .withMessageContaining("field");
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> Guard.notBlank("  ", "field"))
                .withMessageContaining("must not be blank");
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> Guard.maxLength("abcd", 3, "field"))
                .withMessageContaining("must not exceed 3");
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> Guard.matches("abc", Pattern.compile("\\d+"), "field"))
                .withMessageContaining("invalid format");
        assertThatExceptionOfType(DomainValidationException.class).isThrownBy(() -> Guard.notNegative(-1, "field"));
        assertThatExceptionOfType(DomainValidationException.class).isThrownBy(() -> Guard.notNegative(-1L, "field"));
        assertThatExceptionOfType(DomainValidationException.class).isThrownBy(() -> Guard.positive(0, "field"));
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> Guard.isTrue(false, "boom"))
                .withMessage("boom");
    }

    @Test
    @DisplayName("valid values are returned unchanged")
    void validValuesPassThrough() {
        // Act + Assert
        assertThat(Guard.notNull("value", "field")).isEqualTo("value");
        assertThat(Guard.notBlank("value", "field")).isEqualTo("value");
        assertThat(Guard.maxLength("value", 5, "field")).isEqualTo("value");
        assertThat(Guard.maxLength(null, 5, "field")).isNull();
        assertThat(Guard.matches("123", Pattern.compile("\\d+"), "field")).isEqualTo("123");
        assertThat(Guard.notNegative(0, "field")).isZero();
        assertThat(Guard.notNegative(0L, "field")).isZero();
        assertThat(Guard.positive(1, "field")).isEqualTo(1);
    }

    @Test
    @DisplayName("copyOf turns null into an empty immutable collection")
    void copyOfHandlesNull() {
        // Act
        List<String> list = Guard.copyOf((List<String>) null);
        Set<String> set = Guard.copyOf((Set<String>) null);
        Map<String, String> map = Guard.copyOf((Map<String, String>) null);

        // Assert
        assertThat(list).isEmpty();
        assertThat(set).isEmpty();
        assertThat(map).isEmpty();
        assertThat(Guard.copyOf(List.of("a"))).containsExactly("a");
    }
}
