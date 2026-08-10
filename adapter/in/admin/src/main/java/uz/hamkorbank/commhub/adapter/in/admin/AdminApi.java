package uz.hamkorbank.commhub.adapter.in.admin;

/**
 * Paths and conventions of the admin REST BFF (§11.2, UI-02, UI-03).
 *
 * <p>A base path of its own, separate from the {@code /api/v1} of the source systems, because the two
 * are different APIs with different callers, different authentication and different compatibility
 * promises. §8.2 is a published contract the Bank's systems integrate against and which may not move
 * under them; this one is the backend of one SPA that ships with it, and the security chain of SEC-02
 * is applied to a prefix rather than to a list of endpoints somebody has to remember to extend.
 */
public final class AdminApi {

    /** Base path of the admin BFF. */
    public static final String BASE = "/api/admin/v1";

    public static final String DASHBOARD = BASE + "/dashboard";
    public static final String BATCHES = BASE + "/batches";
    public static final String MESSAGES = BASE + "/messages";
    public static final String DLQ = BASE + "/dlq";
    public static final String STREAMS = BASE + "/streams";
    public static final String CHANNELS = BASE + "/channels";
    public static final String PROVIDERS = BASE + "/providers";
    public static final String ROUTING = BASE + "/routing";
    public static final String TEMPLATES = BASE + "/templates";
    public static final String SUPPRESSIONS = BASE + "/suppressions";
    public static final String STATISTICS = BASE + "/statistics";
    public static final String AUDIT = BASE + "/audit";
    public static final String ADMINISTRATION = BASE + "/administration";

    /** Отправка, инициированная оператором (ADR-0038); §11.2 такого раздела не знает — он новее. */
    public static final String SEND = BASE + "/send";

    /**
     * Justification of an action, kept in the audit journal (FR-7.3, SEC-03).
     *
     * <p>A header rather than a body field because the critical operations of SEC-03 are mostly
     * bodiless — a kill switch is a POST with nothing in it — and one place for the reason means the
     * journal reads the same way whichever button was pressed.
     */
    public static final String REASON_HEADER = "X-Commhub-Reason";

    /** Media type of the CSV exports of §11.2 ("Статистика/Отчёты", "Аудит"). */
    public static final String TEXT_CSV = "text/csv";

    private AdminApi() {}
}
