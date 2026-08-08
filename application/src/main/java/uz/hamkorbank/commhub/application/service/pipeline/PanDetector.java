package uz.hamkorbank.commhub.application.service.pipeline;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Detects a full card number in message content (PCI DSS, SEC-05).
 *
 * <p>A candidate is any digit group of 13…19 digits, optionally split by spaces or hyphens in groups
 * of four; it counts as a PAN when it also passes the Luhn checksum, which keeps ordinary numbers —
 * amounts, account references, OTP codes — out of the way.
 *
 * <p>Content that carries a PAN is rejected with {@code PAN_DETECTED} and alerted on; a card number
 * must never travel in an SMS (SEC-05).
 */
@Component
public class PanDetector {

    private static final Pattern CANDIDATE = Pattern.compile("(?<![0-9])(?:[0-9][ -]?){12,18}[0-9](?![0-9])");
    private static final int MIN_DIGITS = 13;
    private static final int MAX_DIGITS = 19;

    /** Whether the text contains something that is a card number by structure and checksum. */
    public boolean containsPan(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        Matcher matcher = CANDIDATE.matcher(text);
        while (matcher.find()) {
            String digits = matcher.group().replaceAll("[ -]", "");
            if (digits.length() >= MIN_DIGITS && digits.length() <= MAX_DIGITS && passesLuhn(digits)) {
                return true;
            }
        }
        return false;
    }

    /** Luhn checksum (ISO/IEC 7812-1). */
    private static boolean passesLuhn(String digits) {
        int sum = 0;
        boolean doubled = false;
        for (int index = digits.length() - 1; index >= 0; index--) {
            int digit = digits.charAt(index) - '0';
            if (doubled) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            doubled = !doubled;
        }
        return sum % 10 == 0;
    }
}
