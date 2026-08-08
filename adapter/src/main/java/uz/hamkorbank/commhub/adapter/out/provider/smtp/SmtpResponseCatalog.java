package uz.hamkorbank.commhub.adapter.out.provider.smtp;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.SendFailedException;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import uz.hamkorbank.commhub.adapter.out.provider.support.ProviderCallException;
import uz.hamkorbank.commhub.domain.model.type.ErrorClass;

/**
 * What an SMTP reply means (§9.3, EM-01, EM-02, PR-01).
 *
 * <p>This is the email counterpart of the error tables of §18.1 and §18.2, except that the table is a
 * standard: RFC 5321 fixes the reply codes and RFC 3463 the enhanced status that follows them. The three
 * questions the sending path asks are the same as for SMS.
 *
 * <p><b>Is it the relay's problem or the message's?</b> Everything {@code 4xx} is the relay saying "not
 * now", and so is a connection that failed outright — those are thrown, so retry and the circuit breaker
 * see them (PR-01). Everything {@code 5xx} about a particular message — an unknown recipient, a body the
 * relay will not carry — is returned as a rejection, because a stream of them says nothing about the
 * relay's health and must never open its breaker.
 *
 * <p><b>Is it blocking?</b> A refused or failed authentication is the email version of Playmobile's account
 * lock (§18.1 code 102): it will not get better with the next twenty messages, and every one of them costs a
 * message its SLA. It opens the breaker at once.
 *
 * <p><b>Does the address itself have to stop being used?</b> Only when the relay says the mailbox does not
 * exist — enhanced status {@code 5.1.1}…{@code 5.1.3} — never on a bare {@code 550}. A bare {@code 550} is
 * as often a content or policy refusal, and suppressing a live customer address because a spam filter did
 * not like one wording is a much worse failure than sending them one more email (FR-5.1, EM-02).
 */
public final class SmtpResponseCatalog {

    public static final String CONNECT_FAILED_CODE = "SMTP_CONNECT";
    public static final String SEND_FAILED_CODE = "SMTP_SEND";

    /** Authentication required or refused (RFC 4954): the integration is down, not the message. */
    private static final Pattern AUTH_CODES = Pattern.compile("^(530|534|535|538)$");

    /** Leading three-digit reply code of an SMTP answer, as it appears in the exception text. */
    private static final Pattern REPLY_CODE = Pattern.compile("(?<![\\d.])([245]\\d\\d)(?![\\d.])");

    /** Enhanced status code of RFC 3463: {@code class.subject.detail}. */
    private static final Pattern ENHANCED_STATUS = Pattern.compile("\\b([245])\\.(\\d{1,3})\\.(\\d{1,3})\\b");

    /** Enhanced subjects that mean "this mailbox does not exist" (RFC 3463 §3.2). */
    private static final Pattern BAD_MAILBOX_STATUS = Pattern.compile("^5\\.1\\.[123]$");

    private SmtpResponseCatalog() {}

    /**
     * Classifies a reply code.
     *
     * @param replyCode three-digit SMTP reply, or {@code null} when the exchange produced none
     */
    public static ErrorClass classify(String replyCode) {
        if (replyCode == null || replyCode.isBlank()) {
            // Ответа не было вовсе — обрыв, таймаут, отказ в соединении: это всегда «попробуй ещё раз».
            return ErrorClass.RETRYABLE;
        }
        if (AUTH_CODES.matcher(replyCode).matches()) {
            return ErrorClass.BLOCKING;
        }
        return replyCode.charAt(0) == '4' ? ErrorClass.RETRYABLE : ErrorClass.NON_RETRYABLE;
    }

    /**
     * Whether the failure says something about the relay rather than about the message (PR-01).
     *
     * <p>Only these are thrown; the rest come back as a rejected ack and leave the breaker closed.
     */
    public static boolean countsAsProviderFailure(String replyCode) {
        return classify(replyCode) != ErrorClass.NON_RETRYABLE;
    }

    /**
     * Whether the address must be put on the suppression list (EM-02, FR-5.1).
     *
     * <p>Deliberately narrow: only an explicit "no such mailbox" from the relay. Everything else — a full
     * mailbox, a policy block, a greylist — is about this delivery, not about the address.
     */
    public static boolean invalidatesRecipient(String enhancedStatus) {
        return enhancedStatus != null
                && BAD_MAILBOX_STATUS.matcher(enhancedStatus).matches();
    }

    /** Reply code carried somewhere in the exception chain, if the relay sent one. */
    public static Optional<String> replyCodeOf(Throwable failure) {
        return firstMatch(failure, REPLY_CODE, 1);
    }

    /** Enhanced status of RFC 3463 carried in the exception chain, if the relay sent one. */
    public static Optional<String> enhancedStatusOf(Throwable failure) {
        return firstMatch(failure, ENHANCED_STATUS, 0);
    }

    /**
     * Turns a failure that happened before any message-level verdict into the exception the executor reads.
     *
     * <p>Used for connecting and for anything the adapter could not interpret: without an answer from the
     * relay there is nothing to attribute to the message, so it is the integration that is failing.
     */
    public static ProviderCallException toCallException(Throwable failure) {
        if (failure instanceof AuthenticationFailedException) {
            return ProviderCallException.blocking("535", "the relay refused the credentials: " + failure.getMessage());
        }
        if (isTimeout(failure)) {
            return ProviderCallException.timeout(failure.getMessage(), failure);
        }
        Optional<String> replyCode = replyCodeOf(failure);
        if (replyCode.isPresent() && classify(replyCode.get()) == ErrorClass.BLOCKING) {
            return ProviderCallException.blocking(replyCode.get(), failure.getMessage());
        }
        return ProviderCallException.retryable(replyCode.orElse(CONNECT_FAILED_CODE), describe(failure));
    }

    /** Whether the connection this failure happened on may still be reused. */
    public static boolean isConnectionSuspect(Throwable failure) {
        if (failure instanceof SendFailedException sendFailure && sendFailure.getInvalidAddresses() != null) {
            // Отвергнутый адрес — это ответ по соединению, а не его обрыв: соединение живо и пригодно.
            return false;
        }
        return replyCodeOf(failure).map(code -> code.startsWith("4")).orElse(true);
    }

    private static boolean isTimeout(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SocketTimeoutException) {
                return true;
            }
        }
        return false;
    }

    private static String describe(Throwable failure) {
        String message = failure.getMessage();
        if (message != null && !message.isBlank()) {
            return message;
        }
        Throwable cause = failure.getCause();
        if (cause instanceof IOException && cause.getMessage() != null) {
            return cause.getMessage();
        }
        return failure.getClass().getSimpleName();
    }

    /**
     * First match of the pattern in the messages of the exception chain.
     *
     * @param group capturing group to return; {@code 0} is the whole match
     */
    private static Optional<String> firstMatch(Throwable failure, Pattern pattern, int group) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message == null) {
                continue;
            }
            Matcher matcher = pattern.matcher(message);
            if (matcher.find()) {
                return Optional.of(matcher.group(group));
            }
        }
        return Optional.empty();
    }
}
