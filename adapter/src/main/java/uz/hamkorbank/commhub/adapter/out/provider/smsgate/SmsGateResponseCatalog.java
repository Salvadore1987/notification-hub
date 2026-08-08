package uz.hamkorbank.commhub.adapter.out.provider.smsgate;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import uz.hamkorbank.commhub.domain.model.type.ErrorClass;

/**
 * The {@code status.code} table of §18.2: what SMS Gate's answer means for the message (SG-01, SG-02).
 *
 * <p>Four outcomes, and each one exists because it leads somewhere different:
 *
 * <ul>
 *   <li><b>success</b> (0) — accepted, {@code id} and {@code parts} come back with it;
 *   <li><b>blocking</b> (10–13) — login, key or partner is wrong. Not a message problem: every message
 *       sent to SMS Gate will fail identically until someone fixes the credentials, so the breaker
 *       opens and the channel falls back (§18.2, FR-6.3, PR-01);
 *   <li><b>retryable elsewhere</b> (1, the 50 SMS/hour/number spam limit) — the message is fine and the
 *       provider is fine; this number has simply had its hour's worth here. Retrying on SMS Gate would
 *       fail the same way for an hour, so it is reported as retryable to let the saga fail over, but it
 *       is deliberately not counted as a provider failure and never retried inside the attempt;
 *   <li><b>server error</b> (27) — transient on their side, counted and retried like an HTTP 5xx;
 *   <li><b>non-retryable</b> — everything else: the number, the sender or the text is refused, and
 *       refusing it again changes nothing.
 * </ul>
 *
 * <p>Code 20 (receiver in blacklist) additionally invalidates the address: §18.2 makes the recipient a
 * suppression candidate, and the ack says so through {@code invalidRecipient} (FR-5.1).
 */
public final class SmsGateResponseCatalog {

    public static final String SUCCESS = "0";

    /** 1 — the 50 SMS per hour per number anti-spam limit (§18.2, FR-2.5). */
    public static final String SPAM_LIMIT = "1";

    /** 20 — the number is on the provider's blacklist; a suppression candidate (§18.2, FR-5.1). */
    public static final String BLACKLISTED = "20";

    /** 27 — server side error (§18.2). */
    public static final String SERVER_ERROR = "27";

    /** 10–13: login required, key required, partner not found, wrong key (§18.2). */
    private static final Set<String> BLOCKING = Set.of("10", "11", "12", "13");

    private static final Map<String, String> DESCRIPTIONS = Map.ofEntries(
            Map.entry("0", "success"),
            Map.entry("1", "spam: 50 SMS per hour per number"),
            Map.entry("10", "login required"),
            Map.entry("11", "key required"),
            Map.entry("12", "partner not found"),
            Map.entry("13", "wrong key"),
            Map.entry("14", "phone required"),
            Map.entry("15", "sender required"),
            Map.entry("16", "wrong sender"),
            Map.entry("17", "phone not match to pattern"),
            Map.entry("18", "text required"),
            Map.entry("19", "text too long"),
            Map.entry("20", "receiver in blacklist"),
            Map.entry("21", "unknown operator"),
            Map.entry("22", "distribution name required"),
            Map.entry("23", "distribution not created"),
            Map.entry("24", "distribution_id is required"),
            Map.entry("25", "distribution not found"),
            Map.entry("26", "distribution is expired"),
            Map.entry("27", "server side error"));

    /**
     * Batch element states of §18.2 that are not {@code ok}, mapped onto the response code that says
     * the same thing — so one table classifies both forms of the API (SG-02).
     */
    private static final Map<String, String> ITEM_STATES = Map.of(
            "duplicate", "19",
            "blacklist", BLACKLISTED,
            "unknown operator", "21",
            "format error", "17",
            "empty", "14",
            "empty text", "18");

    private SmsGateResponseCatalog() {}

    public static boolean isSuccess(String code) {
        return SUCCESS.equals(normalize(code));
    }

    /** Classification of §18.2; an undocumented code is refused permanently, as with §18.1. */
    public static ErrorClass classify(String code) {
        String value = normalize(code);
        if (BLOCKING.contains(value)) {
            return ErrorClass.BLOCKING;
        }
        if (SERVER_ERROR.equals(value)) {
            return ErrorClass.RETRYABLE;
        }
        // The spam limit is retryable only on another provider — see the class comment.
        return SPAM_LIMIT.equals(value) ? ErrorClass.RETRYABLE : ErrorClass.NON_RETRYABLE;
    }

    /**
     * Whether the failure is worth counting against the provider's health.
     *
     * <p>The spam limit is not: the integration is working exactly as agreed, and letting a busy
     * afternoon of one-time passwords to one number open a breaker would take the provider away from
     * every other message.
     */
    public static boolean countsAsProviderFailure(String code) {
        String value = normalize(code);
        return BLOCKING.contains(value) || SERVER_ERROR.equals(value);
    }

    /** Whether the address should be treated as no longer deliverable (§18.2 code 20, FR-5.1). */
    public static boolean invalidatesRecipient(String code) {
        return BLACKLISTED.equals(normalize(code));
    }

    public static String describe(String code) {
        String value = normalize(code);
        return DESCRIPTIONS.getOrDefault(value, "undocumented SMS Gate code " + value);
    }

    /** Response code equivalent to a batch element state such as {@code blacklist} (§18.2, SG-02). */
    public static String codeOfItemState(String state) {
        if (state == null || state.isBlank()) {
            return null;
        }
        String value = state.trim().toLowerCase(Locale.ROOT);
        return "ok".equals(value) ? SUCCESS : ITEM_STATES.get(value);
    }

    private static String normalize(String code) {
        return code == null ? "" : code.trim();
    }
}
