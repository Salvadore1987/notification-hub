package uz.hamkorbank.commhub.adapter.out.provider.smtp;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import uz.hamkorbank.commhub.domain.model.type.MessageStatus;
import uz.hamkorbank.commhub.domain.model.type.SuppressionReason;

/**
 * What a non-delivery report means for the message and for the address (EM-02, RFC 3463).
 *
 * <p>Two answers, and keeping them apart is the whole point — the same rule the Hub already applies to SMS
 * Gate's {@code InBlackList} (§18.2 code 7). The canonical status says what happened to <em>this</em>
 * message; the suppression says what happens to every next one. A report can carry either, both, or neither.
 *
 * <p><b>Status.</b> A {@code failed} report with a {@code 5.x.x} status is a permanent non-delivery:
 * {@code UNDELIVERED}. A {@code delivered} report — some servers send them, though the Hub does not ask for
 * them — is a genuine {@code DELIVERED}, and taking it is free evidence. A {@code delayed} report is applied
 * as nothing at all: "still trying" is not an outcome, ST-01 has no transition for it, and the relay is going
 * to retry on its own schedule regardless. That is the same decision as SMS Gate's code 6 (Unknown).
 *
 * <p><b>Suppression.</b> Only {@code 5.1.x} — the RFC 3463 subject for a bad destination address — puts the
 * address on the suppression list, plus {@code 5.2.1} for a mailbox the server says is disabled. Everything
 * else that is permanent is permanent about the <em>message</em>: a full mailbox, a message too large, a
 * policy or content refusal. Suppressing a live customer's address because one wording tripped a spam filter
 * is a far worse failure than one more email that bounces, and it is invisible until the customer complains
 * that the Bank stopped writing to them (FR-5.1).
 */
public final class BounceCatalog {

    /** Bad destination address: no such user, ambiguous, invalid (RFC 3463 §3.2). */
    private static final Pattern BAD_ADDRESS = Pattern.compile("^5\\.1\\.[0123]$");

    /** Mailbox exists but is disabled — the account is closed, which is as permanent as a bad address. */
    private static final Pattern DISABLED_MAILBOX = Pattern.compile("^5\\.2\\.1$");

    private BounceCatalog() {}

    /**
     * Canonical status the report applies, if it applies one (ST-01, ST-03).
     *
     * @return empty when the report says nothing final — a delay, or a failure without a status code
     */
    public static Optional<MessageStatus> statusOf(BounceReport report) {
        if (report == null || report.action() == null) {
            return Optional.empty();
        }
        return switch (report.action().toLowerCase(Locale.ROOT)) {
            case BounceReport.ACTION_DELIVERED -> Optional.of(MessageStatus.DELIVERED);
            // A 4.x.x status on a failed action happens — a relay that gave up after its own retries reports
            // the last transient code it saw. The message is undelivered either way; only the address escapes
            // suppression, which suppressionOf() already sees to.
            case BounceReport.ACTION_FAILED -> Optional.of(MessageStatus.UNDELIVERED);
            default -> Optional.empty();
        };
    }

    /** Whether the report also means the address itself must stop being used (EM-02, FR-5.1). */
    public static Optional<SuppressionReason> suppressionOf(BounceReport report) {
        if (report == null || !BounceReport.ACTION_FAILED.equalsIgnoreCase(report.action())) {
            return Optional.empty();
        }
        String status = report.status();
        if (status == null) {
            return Optional.empty();
        }
        boolean permanentAddressFailure = BAD_ADDRESS.matcher(status).matches()
                || DISABLED_MAILBOX.matcher(status).matches();
        return permanentAddressFailure ? Optional.of(SuppressionReason.HARD_BOUNCE) : Optional.empty();
    }

    /** Whether the report is a hard bounce in the sense EM-02 uses the word. */
    public static boolean isHardBounce(BounceReport report) {
        return suppressionOf(report).isPresent();
    }
}
