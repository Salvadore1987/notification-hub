package uz.hamkorbank.commhub.domain.model.vo;

import java.util.Locale;
import java.util.regex.Pattern;
import uz.hamkorbank.commhub.domain.exception.DomainValidationException;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Email address of a recipient or sender (FR-1.4, RFC 5322 in its practical subset).
 *
 * <p>The local part is kept as provided; the domain part is normalised to lower case so that
 * suppression lookups by hash are stable (FR-5.1, DB-04).
 */
public record EmailAddress(String value) {

    public static final int MAX_LENGTH = 254;

    private static final Pattern PATTERN =
            Pattern.compile("^[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+(\\.[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+)*"
                    + "@[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)+$");

    public EmailAddress {
        Guard.maxLength(value, MAX_LENGTH, "EmailAddress.value");
        Guard.matches(value, PATTERN, "EmailAddress.value");
    }

    public static EmailAddress of(String value) {
        Guard.notBlank(value, "EmailAddress.value");
        int separator = value.lastIndexOf('@');
        if (separator < 0) {
            throw new DomainValidationException("EmailAddress.value has an invalid format: missing '@'");
        }
        String localPart = value.substring(0, separator);
        String domainPart = value.substring(separator + 1).toLowerCase(Locale.ROOT);
        return new EmailAddress(localPart + "@" + domainPart);
    }

    public String localPart() {
        return value.substring(0, value.lastIndexOf('@'));
    }

    public String domain() {
        return value.substring(value.lastIndexOf('@') + 1);
    }

    /** Masked form for logs and UI: {@code i***n@example.com} (DB-04, OBS-03). */
    public String masked() {
        String localPart = localPart();
        String maskedLocalPart = localPart.length() <= 2
                ? localPart.charAt(0) + "***"
                : localPart.charAt(0) + "***" + localPart.charAt(localPart.length() - 1);
        return maskedLocalPart + "@" + domain();
    }

    @Override
    public String toString() {
        return masked();
    }
}
