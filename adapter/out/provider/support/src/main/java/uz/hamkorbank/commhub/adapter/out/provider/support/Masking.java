package uz.hamkorbank.commhub.adapter.out.provider.support;

import uz.hamkorbank.commhub.domain.model.vo.EmailAddress;
import uz.hamkorbank.commhub.domain.model.vo.Msisdn;

/**
 * What a provider adapter is allowed to write into a log line (PR-03, OBS-03, SEC-06).
 *
 * <p>The traffic of this layer is exactly the traffic that carries personal data: a phone number and
 * the text sent to it — which for the Hub's main use case is a one-time password. Provider logs are
 * also the ones operations reads most, so the masking has to happen at the point of writing and not in
 * a log pipeline that a misconfiguration can switch off.
 *
 * <p>Nothing here is reversible. The number keeps enough shape to be recognised in a support call
 * ({@code 99890***4567}), the text keeps only its size, and a credential keeps nothing at all.
 */
public final class Masking {

    private static final String REDACTED = "***";

    private Masking() {}

    /** Recipient number in the masked form of DB-04: {@code 99890***4567}. */
    public static String msisdn(Msisdn msisdn) {
        return msisdn == null ? "-" : msisdn.masked();
    }

    /** Same for a number that has not been parsed into the value object yet, e.g. from a callback. */
    public static String msisdn(String raw) {
        if (raw == null || raw.isBlank()) {
            return "-";
        }
        String trimmed = raw.trim();
        if (trimmed.length() <= 7) {
            return REDACTED;
        }
        return trimmed.substring(0, 5) + REDACTED + trimmed.substring(trimmed.length() - 4);
    }

    /** Recipient email in the masked form of DB-04: {@code i***n@example.com} (EM-01, OBS-03). */
    public static String email(EmailAddress email) {
        return email == null ? "-" : email.masked();
    }

    /**
     * Same for an address that has not been parsed into the value object yet, e.g. from a bounce report.
     *
     * <p>The domain survives masking on purpose: it is not personal data, and "everything to this domain is
     * bouncing" is the one thing an operator reads a bounce log for.
     */
    public static String email(String raw) {
        if (raw == null || raw.isBlank()) {
            return "-";
        }
        String trimmed = raw.trim();
        int at = trimmed.lastIndexOf('@');
        if (at <= 0) {
            return REDACTED;
        }
        String localPart = trimmed.substring(0, at);
        String masked = localPart.length() <= 2
                ? localPart.charAt(0) + REDACTED
                : localPart.charAt(0) + REDACTED + localPart.charAt(localPart.length() - 1);
        return masked + trimmed.substring(at);
    }

    /**
     * Message text reduced to its size.
     *
     * <p>No prefix is kept. A prefix would be enough to identify the template, and the templates the
     * Bank sends are recognisable — "Kod: " is followed by exactly the digits nobody may log.
     */
    public static String text(String text) {
        return text == null ? "-" : "[" + text.length() + " chars]";
    }

    /** Credential in a log line, i.e. nothing. Present so that the intent is visible at the call site. */
    public static String secret(String value) {
        return value == null || value.isBlank() ? "-" : REDACTED;
    }

    /**
     * Provider response body for the error log, truncated and stripped of line breaks.
     *
     * <p>Providers echo the request back in their error descriptions often enough that a body cannot be
     * logged whole; the first characters carry the code and the reason, which is what the operator needs.
     */
    public static String body(String body, int maxLength) {
        if (body == null || body.isBlank()) {
            return "-";
        }
        String flattened = body.replaceAll("\\s+", " ").trim();
        return flattened.length() <= maxLength ? flattened : flattened.substring(0, maxLength) + "…";
    }
}
