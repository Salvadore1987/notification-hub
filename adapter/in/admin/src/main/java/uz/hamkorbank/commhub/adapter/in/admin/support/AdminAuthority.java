package uz.hamkorbank.commhub.adapter.in.admin.support;

import uz.hamkorbank.commhub.adapter.in.rest.security.Roles;

/**
 * The {@code @PreAuthorize} expressions of §11.2, written once (SEC-03, FR-7.2, UI-02).
 *
 * <p>An annotation takes a compile-time constant, so the alternative to this class is the same SpEL
 * string retyped at every endpoint. That is not a formatting preference: a mistyped role in a
 * {@code @PreAuthorize} is not a compilation error and not a test failure — it is an endpoint nobody can
 * reach, or one everybody can — and the roles that go with a section of §11.2 are decided per section,
 * not per method.
 *
 * <p>{@link Roles#ADMIN} is not implicitly granted the others. Where §11.2 writes "OPERATOR+" the plus
 * is spelled out here as the roles above it, so what a role may do is visible rather than inherited from
 * an ordering nobody wrote down.
 *
 * <p>There is no escape hatch in these expressions and that is deliberate (ADR-0037). They used to begin
 * with {@code @adminAccess.open()} so that a contour without an OIDC issuer warned instead of refusing
 * everybody; since the local stack ships a Keycloak of its own there is no such contour, and a
 * disjunction that grants everything under a condition nobody re-reads is how a panel ends up open.
 */
public final class AdminAuthority {

    /** Every role the panel has; used by the screens §11.2 marks "все". */
    public static final String ANY = "hasAnyRole('" + Roles.ADMIN + "','" + Roles.OPERATOR + "','"
            + Roles.ANALYST + "','" + Roles.VIEWER + "','" + Roles.SECURITY_AUDITOR + "','"
            + Roles.TEMPLATE_MANAGER + "')";

    /** Configuration: providers, routing, streams, kill switch, system parameters. */
    public static final String ADMIN = "hasRole('" + Roles.ADMIN + "')";

    /** Running traffic: batch actions, DLQ retries. §11.2 "OPERATOR+". */
    public static final String OPERATOR = "hasAnyRole('" + Roles.ADMIN + "','" + Roles.OPERATOR + "')";

    /**
     * Reading messages and batches: the operator roles plus {@link Roles#VIEWER}, whose addresses are
     * masked on the way out rather than withheld (§11.2 "Сообщения").
     */
    public static final String OPERATOR_OR_VIEWER =
            "hasAnyRole('" + Roles.ADMIN + "','" + Roles.OPERATOR + "','" + Roles.VIEWER + "')";

    /** Reports and their exports. §11.2 "ANALYST+". */
    public static final String ANALYST = "hasAnyRole('" + Roles.ADMIN + "','" + Roles.ANALYST + "')";

    /** The audit journal: the auditor and the administrator, and deliberately nobody else. */
    public static final String AUDITOR = "hasAnyRole('" + Roles.ADMIN + "','" + Roles.SECURITY_AUDITOR + "')";

    /** Template authoring and review (FR-4.2). */
    public static final String TEMPLATE_MANAGER = "hasAnyRole('" + Roles.ADMIN + "','" + Roles.TEMPLATE_MANAGER + "')";

    /** The suppression list, which §11.2 gives to both ADMIN and OPERATOR. */
    public static final String SUPPRESSION = OPERATOR;

    /**
     * Sending from the panel (ADR-0038).
     *
     * <p>The same pair that already controls running traffic: the right to send and the right to stop
     * what is being sent belong together, and a separate role would only make one of them harder to get.
     */
    public static final String SENDER = OPERATOR;

    private AdminAuthority() {}
}
