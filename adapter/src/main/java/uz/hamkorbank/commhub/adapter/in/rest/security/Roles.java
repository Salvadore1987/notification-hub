package uz.hamkorbank.commhub.adapter.in.rest.security;

/**
 * The RBAC roles of the admin panel, exactly as {@code app_role} seeds them in V7 (FR-7.2, SEC-03).
 *
 * <p>Constants rather than strings at the annotation sites: a mistyped role in a {@code @PreAuthorize}
 * is not a compilation error and not a test failure — it is an endpoint nobody can reach, or worse, one
 * everybody can. They are also the mapping target of the SSO groups, so the two ends of "who may do
 * what" are written once.
 *
 * <p>Least privilege is a property of how these are used, not of the list: an endpoint names the one
 * role that needs it, and {@link #ADMIN} is not implicitly granted the others. The critical operations
 * of SEC-03 — kill switch, provider changes — carry both a role check and an audit entry, and the audit
 * entry is the one that is not optional.
 */
public final class Roles {

    /** Providers, routing, streams, users, kill switch. */
    public static final String ADMIN = "ADMIN";

    /** Running traffic: pause, stop, retry, DLQ, suppression list. */
    public static final String OPERATOR = "OPERATOR";

    /** Templates: authoring, review and publication (FR-4.2). */
    public static final String TEMPLATE_MANAGER = "TEMPLATE_MANAGER";

    /** Statistics, reports, export. */
    public static final String ANALYST = "ANALYST";

    /** Reading messages and the dashboard; addresses are masked for this role (DB-04). */
    public static final String VIEWER = "VIEWER";

    /** The audit journal and its export (FR-7.3, SEC-08). */
    public static final String SECURITY_AUDITOR = "SECURITY_AUDITOR";

    /** Prefix Spring Security expects on a role authority. */
    public static final String PREFIX = "ROLE_";

    private Roles() {}

    /** Authority form of a role, for the places that take an authority rather than a role name. */
    public static String authority(String role) {
        return PREFIX + role;
    }
}
