package uz.hamkorbank.commhub.adapter.out.provider.apns;

import java.util.Set;
import uz.hamkorbank.commhub.domain.model.type.ErrorClass;

/**
 * What an APNs answer means for the message, the token and the provider (PU-08, §9.4.2).
 *
 * <p>Apple answers a refused notification with an HTTP status and a {@code reason} string, and the
 * reason is what carries the meaning: a {@code 400} covers both "this payload is malformed" and "this
 * token belongs to another application", and only the second retires an address.
 *
 * <p>The same split as every other adapter here (PR-01): only what says something about APNs itself is
 * thrown, and only a thrown answer is retried and counted by the circuit breaker. A campaign against
 * uninstalled applications is a stream of {@code 410 Unregistered}, and APNs is healthy throughout.
 *
 * <p>Two entries deserve their reasoning in writing:
 *
 * <ul>
 *   <li>{@code ExpiredProviderToken} and {@code InvalidProviderToken} are <b>blocking</b> and drop the
 *       cached JWT. Blocking because no iOS notification can be delivered until it is fixed — the email
 *       version of this is an SMTP authentication failure, the SMS version is Playmobile 102 (§18.1) —
 *       and dropping the token first means a merely expired one recovers on the next message rather
 *       than needing an operator.
 *   <li>{@code TooManyRequests} and {@code TooManyProviderTokenUpdates} are <b>retryable but not a
 *       provider failure</b>: being throttled is the Hub going too fast, and opening the breaker would
 *       also stop the traffic that was within the limit. Same call as FCM {@code QUOTA_EXCEEDED} and
 *       the spam limit of SMS Gate (§18.2 code 1).
 * </ul>
 */
public final class ApnsResponseCatalog {

    /** The application was uninstalled; arrives with HTTP 410 (PU-08). */
    public static final String UNREGISTERED = "Unregistered";

    public static final String BAD_DEVICE_TOKEN = "BadDeviceToken";
    public static final String DEVICE_TOKEN_NOT_FOR_TOPIC = "DeviceTokenNotForTopic";
    public static final String EXPIRED_PROVIDER_TOKEN = "ExpiredProviderToken";
    public static final String INVALID_PROVIDER_TOKEN = "InvalidProviderToken";
    public static final String MISSING_PROVIDER_TOKEN = "MissingProviderToken";
    public static final String TOO_MANY_REQUESTS = "TooManyRequests";
    public static final String TOO_MANY_PROVIDER_TOKEN_UPDATES = "TooManyProviderTokenUpdates";
    public static final String PAYLOAD_TOO_LARGE = "PayloadTooLarge";
    public static final String INTERNAL_SERVER_ERROR = "InternalServerError";
    public static final String SERVICE_UNAVAILABLE = "ServiceUnavailable";
    public static final String SHUTDOWN = "Shutdown";

    /** Unknown reason, used when Apple answered without a body the adapter could read. */
    public static final String UNSPECIFIED = "Unspecified";

    /**
     * Reasons that retire the device token (PU-08).
     *
     * <p>{@code DeviceTokenNotForTopic} is included deliberately. It means the token was registered for
     * a different application, which for the Hub is the same statement as a dead token: no notification
     * from this configuration will ever reach it, and keeping it costs a call per message forever.
     */
    private static final Set<String> INVALIDATES_TOKEN =
            Set.of(UNREGISTERED, BAD_DEVICE_TOKEN, DEVICE_TOKEN_NOT_FOR_TOPIC);

    /** Apple's own trouble; the message may go to the next provider or come back later. */
    private static final Set<String> TRANSIENT = Set.of(INTERNAL_SERVER_ERROR, SERVICE_UNAVAILABLE, SHUTDOWN);

    /** The Hub's credentials were refused: nothing iOS can be delivered until it is fixed. */
    private static final Set<String> CREDENTIAL_FAILURES =
            Set.of(EXPIRED_PROVIDER_TOKEN, INVALID_PROVIDER_TOKEN, MISSING_PROVIDER_TOKEN);

    private static final Set<String> THROTTLED = Set.of(TOO_MANY_REQUESTS, TOO_MANY_PROVIDER_TOKEN_UPDATES);

    private ApnsResponseCatalog() {}

    /** Class of an answer; drives retry, failover and the breaker (PR-01, PU-08). */
    public static ErrorClass classify(String reason, int httpStatus) {
        String value = normalize(reason);
        if (CREDENTIAL_FAILURES.contains(value)) {
            return ErrorClass.BLOCKING;
        }
        if (THROTTLED.contains(value) || httpStatus == 429) {
            return ErrorClass.RETRYABLE;
        }
        if (TRANSIENT.contains(value) || httpStatus >= 500) {
            return ErrorClass.RETRYABLE;
        }
        // 400 BadRequest, 403 for anything but the token, 413 PayloadTooLarge, 410 Unregistered:
        // all statements about this notification or this device, which the next attempt would repeat.
        return ErrorClass.NON_RETRYABLE;
    }

    public static boolean invalidatesToken(String reason, int httpStatus) {
        return INVALIDATES_TOKEN.contains(normalize(reason)) || httpStatus == 410;
    }

    /** Whether the JWT must be re-signed before the next call (PU-06, PU-08). */
    public static boolean invalidatesProviderToken(String reason) {
        return CREDENTIAL_FAILURES.contains(normalize(reason));
    }

    /** Whether the answer is thrown, i.e. seen by the retry and the circuit breaker (PR-01). */
    public static boolean countsAsProviderFailure(String reason, int httpStatus) {
        String value = normalize(reason);
        if (THROTTLED.contains(value) || httpStatus == 429) {
            return false;
        }
        return CREDENTIAL_FAILURES.contains(value) || TRANSIENT.contains(value) || httpStatus >= 500;
    }

    /** Sentence kept on the delivery attempt; PU-08 requires the reason to be stored verbatim. */
    public static String describe(String reason, int httpStatus) {
        return "%s (HTTP %d)".formatted(normalize(reason), httpStatus);
    }

    private static String normalize(String reason) {
        return reason == null || reason.isBlank() ? UNSPECIFIED : reason.trim();
    }
}
