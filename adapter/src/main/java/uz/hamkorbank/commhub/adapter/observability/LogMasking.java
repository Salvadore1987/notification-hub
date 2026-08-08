package uz.hamkorbank.commhub.adapter.observability;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Last-resort masking of personal data on its way into a log line (OBS-03, SEC-05, DB-04).
 *
 * <p>The Hub masks at the point of writing — {@code Masking} in the provider framework is the rule, and
 * it produces better output because it knows what it is holding. This class is the safety net under that
 * rule, applied to whatever text reaches the appender: a stack trace with an address in the exception
 * message, a third-party library echoing a request body, a log line written by someone who forgot.
 *
 * <p>Three shapes are recognised and nothing else, so the cost is three matcher runs over a line:
 *
 * <ul>
 *   <li>Uzbek MSISDNs — {@code 998901234567} becomes {@code 99890***4567}, the form §DB-04 asks for and
 *       the one a support call can still be matched against;
 *   <li>e-mail addresses — the local part collapses, the domain survives, because a domain is not
 *       personal data and "everything to this domain fails" is what a bounce log is read for (EM-02);
 *   <li>card numbers — 13 to 19 digits passing Luhn keep only their last four (PCI DSS, SEC-05). The
 *       Luhn check is what keeps a message id or an amount from being mangled into asterisks.
 * </ul>
 *
 * <p>Nothing here is reversible and nothing here is a substitute for not logging the value.
 */
public final class LogMasking {

    private static final Pattern MSISDN = Pattern.compile("\\b998\\d{9}\\b");

    private static final Pattern EMAIL = Pattern.compile("\\b[\\w.+%-]+@[\\w-]+\\.[\\w.-]{2,}\\b");

    private static final Pattern CARD = Pattern.compile("\\b\\d{13,19}\\b");

    private static final String REDACTED = "***";

    private LogMasking() {}

    /** Masked copy of the text; {@code null} and text without a match are returned unchanged. */
    public static String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return maskCards(maskEmails(maskMsisdns(text)));
    }

    private static String maskMsisdns(String text) {
        Matcher matcher = MSISDN.matcher(text);
        if (!matcher.find()) {
            return text;
        }
        matcher.reset();
        return matcher.replaceAll(match -> {
            String number = match.group();
            return number.substring(0, 5) + REDACTED + number.substring(number.length() - 4);
        });
    }

    private static String maskEmails(String text) {
        Matcher matcher = EMAIL.matcher(text);
        if (!matcher.find()) {
            return text;
        }
        matcher.reset();
        return matcher.replaceAll(match -> {
            String address = match.group();
            int at = address.lastIndexOf('@');
            String local = address.substring(0, at);
            String masked = local.length() <= 2
                    ? local.charAt(0) + REDACTED
                    : local.charAt(0) + REDACTED + local.charAt(local.length() - 1);
            return Matcher.quoteReplacement(masked + address.substring(at));
        });
    }

    private static String maskCards(String text) {
        Matcher matcher = CARD.matcher(text);
        if (!matcher.find()) {
            return text;
        }
        matcher.reset();
        return matcher.replaceAll(match -> {
            String digits = match.group();
            return isLuhnValid(digits) ? REDACTED + digits.substring(digits.length() - 4) : digits;
        });
    }

    /**
     * Luhn check of §18.3's PAN detector, repeated here rather than shared with it.
     *
     * <p>The validator in the core answers "may this message be sent"; this one answers "may this text be
     * written down". They are allowed to diverge — a log line must be masked even when the content was
     * accepted — and the domain must not gain a dependency on a logging concern to keep them together.
     */
    private static boolean isLuhnValid(String digits) {
        int sum = 0;
        boolean doubled = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int digit = digits.charAt(i) - '0';
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
