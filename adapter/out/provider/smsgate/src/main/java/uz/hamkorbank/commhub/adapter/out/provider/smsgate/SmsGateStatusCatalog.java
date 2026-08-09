package uz.hamkorbank.commhub.adapter.out.provider.smsgate;

import java.util.Map;
import java.util.Optional;
import uz.hamkorbank.commhub.domain.model.type.MessageStatus;

/**
 * FEEDBACK status codes → the canonical status model (§18.2, SG-02, ST-03).
 *
 * <p>The table of §18.2 with two deliberate departures, both forced by the state machine of §6.3:
 *
 * <ul>
 *   <li><b>6 Unknown</b> maps to nothing. The specification pairs it with the reconciliation of SG-03,
 *       and that is the point: "unknown" is not an outcome, and recording it as {@code UNDELIVERED}
 *       would make the message terminal on the strength of a report that says the provider does not
 *       know. The report is dropped and {@link SmsGateReconciler} asks {@code /api/v2/search} later.
 *   <li><b>7 InBlackList</b> maps to {@code UNDELIVERED} rather than to {@code REJECTED}. A FEEDBACK
 *       report reaches a message that is already {@code SENT_TO_PROVIDER}, and ST-01 has no transition
 *       from there to {@code REJECTED} — rejection belongs to the Hub's own pipeline, before a provider
 *       was ever involved. The suppression consequence of §18.2 is carried separately, by
 *       {@link #invalidatesRecipient(String)}.
 * </ul>
 */
public final class SmsGateStatusCatalog {

    /** 7 InBlackList — the number goes on the suppression list (§18.2, FR-5.1). */
    public static final String IN_BLACK_LIST = "7";

    /** 6 Unknown — resolved by reconciliation, never applied as a status (§18.2, SG-03). */
    public static final String UNKNOWN = "6";

    private static final Map<String, MessageStatus> STATUSES = Map.of(
            "0",
            MessageStatus.SENT_TO_PROVIDER,
            "1",
            MessageStatus.SENT_TO_PROVIDER,
            "2",
            MessageStatus.UNDELIVERED,
            "3",
            MessageStatus.SENT_TO_PROVIDER,
            "4",
            MessageStatus.DELIVERED,
            "5",
            MessageStatus.UNDELIVERED,
            IN_BLACK_LIST,
            MessageStatus.UNDELIVERED);

    private static final Map<String, String> DESCRIPTIONS = Map.of(
            "0",
            "Created",
            "1",
            "Sending",
            "2",
            "Fail",
            "3",
            "Sent",
            "4",
            "Delivered",
            "5",
            "Rejected",
            UNKNOWN,
            "Unknown",
            IN_BLACK_LIST,
            "InBlackList");

    private SmsGateStatusCatalog() {}

    /** Canonical status of a FEEDBACK code, or empty for 6 Unknown and anything undocumented. */
    public static Optional<MessageStatus> canonical(String code) {
        return code == null ? Optional.empty() : Optional.ofNullable(STATUSES.get(code.trim()));
    }

    public static boolean invalidatesRecipient(String code) {
        return code != null && IN_BLACK_LIST.equals(code.trim());
    }

    /** Whether the report merely says "ask again later" (§18.2 code 6, SG-03). */
    public static boolean isUnknown(String code) {
        return code != null && UNKNOWN.equals(code.trim());
    }

    public static String describe(String code) {
        String value = code == null ? "" : code.trim();
        return DESCRIPTIONS.getOrDefault(value, "undocumented SMS Gate status " + value);
    }
}
