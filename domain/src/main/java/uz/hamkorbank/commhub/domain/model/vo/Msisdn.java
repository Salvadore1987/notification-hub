package uz.hamkorbank.commhub.domain.model.vo;

import java.util.regex.Pattern;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Recipient phone number in the only accepted format: {@code 9989xxxxxxxx} — no {@code +}, no
 * spaces (FR-1.4, §9.1).
 */
public record Msisdn(String value) {

    private static final Pattern PATTERN = Pattern.compile("^9989\\d{8}$");
    private static final int PREFIX_LENGTH = 5;
    private static final int SUFFIX_LENGTH = 4;

    public Msisdn {
        Guard.matches(value, PATTERN, "Msisdn.value");
    }

    public static Msisdn of(String value) {
        return new Msisdn(value);
    }

    /**
     * Normalises common input variants ({@code +998...}, spaces, dashes) before validation.
     *
     * <p>Used by inbound adapters; the canonical form stored in the domain stays {@code 9989xxxxxxxx}.
     */
    public static Msisdn normalize(String raw) {
        Guard.notBlank(raw, "Msisdn.value");
        return new Msisdn(raw.replaceAll("[\\s()+-]", ""));
    }

    /** Masked form for logs and UI: {@code 99890***4567} (DB-04, OBS-03). */
    public String masked() {
        return value.substring(0, PREFIX_LENGTH) + "***" + value.substring(value.length() - SUFFIX_LENGTH);
    }

    @Override
    public String toString() {
        return masked();
    }
}
