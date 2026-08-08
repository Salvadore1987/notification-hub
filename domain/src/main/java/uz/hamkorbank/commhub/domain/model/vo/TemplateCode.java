package uz.hamkorbank.commhub.domain.model.vo;

import java.util.Locale;
import java.util.regex.Pattern;
import uz.hamkorbank.commhub.domain.support.Guard;

/** Business code a source system refers a template by, e.g. {@code OTP_LOGIN} (FR-4.1, FR-4.3). */
public record TemplateCode(String value) {

    private static final Pattern PATTERN = Pattern.compile("^[A-Z0-9][A-Z0-9._-]{1,63}$");

    public TemplateCode {
        Guard.matches(value, PATTERN, "TemplateCode.value");
    }

    public static TemplateCode of(String value) {
        Guard.notBlank(value, "TemplateCode.value");
        return new TemplateCode(value.toUpperCase(Locale.ROOT));
    }

    @Override
    public String toString() {
        return value;
    }
}
