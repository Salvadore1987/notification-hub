package uz.hamkorbank.commhub.domain.model.vo;

import java.util.Locale;
import java.util.regex.Pattern;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Stable business code of a provider, e.g. {@code PLAYMOBILE}, {@code SMSGATE} (§6.4, FR-2.1).
 *
 * <p>Deliberately a free-form string and not an enum: a new provider must not require a change in
 * {@code domain/} or {@code application/} (AR-04).
 */
public record ProviderCode(String value) {

    private static final Pattern PATTERN = Pattern.compile("^[A-Z0-9][A-Z0-9_]{1,31}$");

    public ProviderCode {
        Guard.matches(value, PATTERN, "ProviderCode.value");
    }

    public static ProviderCode of(String value) {
        Guard.notBlank(value, "ProviderCode.value");
        return new ProviderCode(value.toUpperCase(Locale.ROOT));
    }

    @Override
    public String toString() {
        return value;
    }
}
