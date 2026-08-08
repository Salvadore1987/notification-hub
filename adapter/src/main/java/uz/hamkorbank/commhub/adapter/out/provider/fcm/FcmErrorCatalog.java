package uz.hamkorbank.commhub.adapter.out.provider.fcm;

import java.util.Locale;
import java.util.Set;
import uz.hamkorbank.commhub.domain.model.type.ErrorClass;

/**
 * What an FCM error means for the message, the token and the provider (PU-04, §9.4.1).
 *
 * <p>The vocabulary is Google's {@code ErrorCode} enum, carried in
 * {@code error.details[].errorCode} of the response body; the HTTP status alone is not enough — a 404
 * is a dead token and a 400 is a malformed message, and only one of them says anything about the
 * address.
 *
 * <p>The split that matters here is the same one every adapter of this project makes (PR-01):
 * {@link #countsAsProviderFailure(String, int)} decides whether the answer is <em>thrown</em>, and only
 * a thrown answer is retried and counted towards the circuit breaker. A campaign hitting ten thousand
 * uninstalled applications produces ten thousand {@code UNREGISTERED} answers, and none of them says
 * anything about FCM's health — the integration is working perfectly, the tokens are dead.
 *
 * <p>Two classifications are deliberate and worth naming:
 *
 * <ul>
 *   <li>{@code QUOTA_EXCEEDED} and HTTP 429 are <b>retryable but not a provider failure</b>. Being
 *       rate-limited is the Hub sending too fast, not FCM being unwell; opening the breaker would stop
 *       the traffic that is under the limit as well. Like the spam limit of SMS Gate (§18.2 code 1).
 *   <li>HTTP 401/403 is <b>blocking</b>. The access token was refused, which does not improve with the
 *       next call and costs every message its SLA until an operator looks; the same call as an SMTP
 *       authentication failure and Playmobile 102 (§18.1). It also drops the cached token, so a merely
 *       expired one recovers on the next message rather than in an hour.
 * </ul>
 */
public final class FcmErrorCatalog {

    /** The application was uninstalled or the token was replaced: the address is dead (PU-04). */
    public static final String UNREGISTERED = "UNREGISTERED";

    public static final String NOT_FOUND = "NOT_FOUND";

    public static final String INVALID_ARGUMENT = "INVALID_ARGUMENT";
    public static final String SENDER_ID_MISMATCH = "SENDER_ID_MISMATCH";
    public static final String THIRD_PARTY_AUTH_ERROR = "THIRD_PARTY_AUTH_ERROR";
    public static final String QUOTA_EXCEEDED = "QUOTA_EXCEEDED";
    public static final String UNAVAILABLE = "UNAVAILABLE";
    public static final String INTERNAL = "INTERNAL";
    public static final String UNSPECIFIED = "UNSPECIFIED_ERROR";

    /** Answers that retire the device token (PU-04). */
    private static final Set<String> INVALIDATES_TOKEN = Set.of(UNREGISTERED, NOT_FOUND);

    /** Answers that are FCM's own trouble rather than the message's. */
    private static final Set<String> TRANSIENT = Set.of(UNAVAILABLE, INTERNAL);

    /**
     * Codes that describe this message or this token, whatever HTTP status carries them.
     *
     * <p>Needed because Google answers three of them with 401/403 — {@code SENDER_ID_MISMATCH} and
     * {@code THIRD_PARTY_AUTH_ERROR} in particular — and a naive "401 means our credentials are bad"
     * rule would open the breaker of the whole provider over one token registered to another sender, or
     * over the iOS certificate of the Bank's app while Android traffic is flowing perfectly. PU-04 lists
     * all three as non-retryable, and that is also the right engineering answer.
     */
    private static final Set<String> MESSAGE_LEVEL =
            Set.of(UNREGISTERED, NOT_FOUND, INVALID_ARGUMENT, SENDER_ID_MISMATCH, THIRD_PARTY_AUTH_ERROR);

    private FcmErrorCatalog() {}

    /** Class of an answer; drives retry, failover and the breaker (PR-01, PU-04). */
    public static ErrorClass classify(String errorCode, int httpStatus) {
        String code = normalize(errorCode);
        if (isAuthFailure(code, httpStatus)) {
            return ErrorClass.BLOCKING;
        }
        if (TRANSIENT.contains(code) || httpStatus >= 500 || isThrottled(code, httpStatus)) {
            return ErrorClass.RETRYABLE;
        }
        // Everything else is about this message or this token: a malformed payload, a token that
        // belongs to another sender, a dead token. The next provider would refuse it just as well.
        return ErrorClass.NON_RETRYABLE;
    }

    /** Whether the answer retires the token and publishes {@code push-token.invalidated} (PU-04). */
    public static boolean invalidatesToken(String errorCode, int httpStatus) {
        String code = normalize(errorCode);
        return INVALIDATES_TOKEN.contains(code) || (httpStatus == 404 && code.equals(UNSPECIFIED));
    }

    /**
     * Whether the answer is thrown, i.e. counted by the retry and the circuit breaker (PR-01).
     *
     * <p>Only what says something about FCM: its own 5xx and its transient codes, plus a refusal of the
     * credentials. Throttling and every message-level refusal are returned instead.
     */
    public static boolean countsAsProviderFailure(String errorCode, int httpStatus) {
        String code = normalize(errorCode);
        if (isThrottled(code, httpStatus)) {
            return false;
        }
        return isAuthFailure(code, httpStatus) || TRANSIENT.contains(code) || httpStatus >= 500;
    }

    /** Whether the cached OAuth token must be dropped before the next call (PU-01). */
    public static boolean invalidatesAccessToken(String errorCode, int httpStatus) {
        return isAuthFailure(normalize(errorCode), httpStatus);
    }

    /** Sentence put on the delivery attempt and, for a dead token, on the outbound event (PR-03). */
    public static String describe(String errorCode, int httpStatus, String message) {
        String code = normalize(errorCode);
        if (message != null && !message.isBlank()) {
            return "%s (HTTP %d): %s".formatted(code, httpStatus, message.trim());
        }
        return "%s (HTTP %d)".formatted(code, httpStatus);
    }

    private static boolean isThrottled(String code, int httpStatus) {
        return QUOTA_EXCEEDED.equals(code) || httpStatus == 429;
    }

    /** The credentials of the Hub were refused — not the message, not the token. */
    private static boolean isAuthFailure(String code, int httpStatus) {
        return (httpStatus == 401 || httpStatus == 403) && !MESSAGE_LEVEL.contains(code);
    }

    private static String normalize(String errorCode) {
        return errorCode == null || errorCode.isBlank()
                ? UNSPECIFIED
                : errorCode.trim().toUpperCase(Locale.ROOT);
    }
}
