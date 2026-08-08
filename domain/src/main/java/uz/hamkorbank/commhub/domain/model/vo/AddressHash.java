package uz.hamkorbank.commhub.domain.model.vo;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;
import uz.hamkorbank.commhub.domain.exception.DomainValidationException;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * SHA-256 hash of a recipient address, used as the suppression-list lookup key (FR-5.1, DB-04).
 *
 * <p>Storing the hash instead of the raw address keeps PII out of the suppression table while still
 * allowing exact-match checks before every send.
 */
public record AddressHash(String value) {

    private static final Pattern PATTERN = Pattern.compile("^[0-9a-f]{64}$");
    private static final String ALGORITHM = "SHA-256";

    public AddressHash {
        Guard.matches(value, PATTERN, "AddressHash.value");
    }

    /** Hashes an already normalised address. */
    public static AddressHash of(String normalizedAddress) {
        Guard.notBlank(normalizedAddress, "normalizedAddress");
        try {
            MessageDigest digest = MessageDigest.getInstance(ALGORITHM);
            byte[] hash =
                    digest.digest(normalizedAddress.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            return new AddressHash(HexFormat.of().formatHex(hash));
        } catch (NoSuchAlgorithmException e) {
            throw new DomainValidationException(ALGORITHM + " is not available in this JVM");
        }
    }

    public static AddressHash ofMsisdn(Msisdn msisdn) {
        Guard.notNull(msisdn, "msisdn");
        return of(msisdn.value());
    }

    public static AddressHash ofEmail(EmailAddress email) {
        Guard.notNull(email, "email");
        return of(email.value());
    }

    public static AddressHash ofPushToken(PushToken pushToken) {
        Guard.notNull(pushToken, "pushToken");
        return of(pushToken.value());
    }

    @Override
    public String toString() {
        return value;
    }
}
