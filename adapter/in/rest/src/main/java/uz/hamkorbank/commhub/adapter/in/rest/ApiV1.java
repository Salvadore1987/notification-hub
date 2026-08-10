package uz.hamkorbank.commhub.adapter.in.rest;

/** Paths and headers of the source-system API v1 (§8.2). */
public final class ApiV1 {

    /** Base path of the source-system API; version in the path, as §8.2 publishes it. */
    public static final String BASE = "/api/v1";

    public static final String MESSAGES = BASE + "/messages";

    public static final String BATCHES = BASE + "/batches";

    /**
     * Identifies who asks for a batch action, for contours where SEC-01 is not switched on yet.
     *
     * <p>Ignored as soon as the caller is authenticated: an identity taken from the token or the client
     * certificate beats one the caller states about itself. Without either, the action is recorded as
     * performed by the system, which is what an unattended source system doing it is (FR-7.3).
     */
    public static final String ACTOR_HEADER = "X-Commhub-Actor";

    /** Free-text justification of a batch action, kept in the audit log (FR-7.3). */
    public static final String REASON_HEADER = "X-Commhub-Reason";

    private ApiV1() {}
}
