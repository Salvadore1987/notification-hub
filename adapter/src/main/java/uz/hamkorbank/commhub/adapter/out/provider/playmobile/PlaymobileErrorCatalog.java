package uz.hamkorbank.commhub.adapter.out.provider.playmobile;

import java.util.Map;
import java.util.Set;
import uz.hamkorbank.commhub.domain.model.type.ErrorClass;

/**
 * The error table of §18.1: what each Playmobile {@code error_code} means for the message (PM-01).
 *
 * <p>This class is the whole of the provider-specific knowledge that drives retry, failover and the
 * circuit breaker. The sending saga never sees a Playmobile code — it sees an {@link ErrorClass}, which
 * is what keeps AR-04 true.
 *
 * <p>Three outcomes, and the distinction between them is what the table exists for:
 *
 * <ul>
 *   <li><b>retryable</b> — 100 Internal server error. Playmobile's problem, and a transient one.
 *   <li><b>blocking</b> — 102 Account lock. Not this message's problem and not the next one's either:
 *       every message sent to Playmobile will fail the same way until someone at the Bank or at
 *       Playmobile unlocks the account, so the breaker opens at once and the channel fails over
 *       (§18.1, FR-6.3).
 *   <li><b>non-retryable</b> — everything else. The document was refused for what is in it, so the
 *       same document refused again changes nothing; the message ends undelivered.
 * </ul>
 *
 * <p>An unlisted code is treated as non-retryable. A code arrives inside an HTTP 400, which already
 * says the request was refused rather than lost; guessing "retryable" for something undocumented would
 * spend the whole retry budget of a message on an error nobody has ever seen.
 */
public final class PlaymobileErrorCatalog {

    /** 100 Internal server error — transient on Playmobile's side (§18.1). */
    public static final String INTERNAL_SERVER_ERROR = "100";

    /** 102 Account lock — trips the circuit breaker and raises an alert (§18.1, PM-01). */
    public static final String ACCOUNT_LOCK = "102";

    private static final Set<String> RETRYABLE = Set.of(INTERNAL_SERVER_ERROR);

    private static final Set<String> BLOCKING = Set.of(ACCOUNT_LOCK);

    /** Descriptions of §18.1, used when the provider sends a code without one. */
    private static final Map<String, String> DESCRIPTIONS = Map.ofEntries(
            Map.entry("100", "Internal server error"),
            Map.entry("101", "Syntax error"),
            Map.entry("102", "Account lock"),
            Map.entry("103", "Empty channel"),
            Map.entry("104", "Invalid priority"),
            Map.entry("105", "Too much IDs"),
            Map.entry("202", "Empty recipient"),
            Map.entry("204", "Empty email address"),
            Map.entry("205", "Empty message-id"),
            Map.entry("206", "Invalid variables"),
            Map.entry("301", "Invalid localtime"),
            Map.entry("302", "Invalid start-datetime"),
            Map.entry("303", "Invalid end-datetime"),
            Map.entry("304", "Invalid allowed-starttime"),
            Map.entry("305", "Invalid allowed-endtime"),
            Map.entry("306", "Invalid send-evenly"),
            Map.entry("401", "Empty originator"),
            Map.entry("402", "Empty application"),
            Map.entry("403", "Empty ttl"),
            Map.entry("404", "Empty content"),
            Map.entry("405", "Content error"),
            Map.entry("406", "Invalid content"),
            Map.entry("407", "Invalid ttl"),
            Map.entry("408", "Invalid attached files"),
            Map.entry("410", "Invalid retry-attempts"),
            Map.entry("411", "Invalid retry-timeout"));

    private PlaymobileErrorCatalog() {}

    /** Classification of §18.1; an unknown code is refused permanently. */
    public static ErrorClass classify(String errorCode) {
        if (errorCode == null || errorCode.isBlank()) {
            return ErrorClass.NON_RETRYABLE;
        }
        String code = errorCode.trim();
        if (BLOCKING.contains(code)) {
            return ErrorClass.BLOCKING;
        }
        return RETRYABLE.contains(code) ? ErrorClass.RETRYABLE : ErrorClass.NON_RETRYABLE;
    }

    /** Whether the code is one §18.1 lists; an unknown one is worth a louder log line. */
    public static boolean isKnown(String errorCode) {
        return errorCode != null && DESCRIPTIONS.containsKey(errorCode.trim());
    }

    /** Description from §18.1, falling back to whatever the provider sent. */
    public static String describe(String errorCode, String providerDescription) {
        if (providerDescription != null && !providerDescription.isBlank()) {
            return providerDescription.trim();
        }
        if (errorCode == null || errorCode.isBlank()) {
            return "Playmobile refused the message without a code";
        }
        String code = errorCode.trim();
        return DESCRIPTIONS.getOrDefault(code, "undocumented Playmobile error " + code);
    }
}
