package uz.hamkorbank.commhub.adapter.in.rest.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How callers prove who they are and what that entitles them to (SEC-01, SEC-02, SEC-03).
 *
 * <p>There is one mechanism and it is always configured: an OIDC token validated against
 * {@code spring.security.oauth2.resourceserver.jwt.issuer-uri}, which is mandatory on every contour
 * because the admin panel is behind SSO on every contour (ADR-0037). Nothing here can switch that off —
 * the only question this record answers is whether <em>source systems</em> on {@code /api/v1} must also
 * present one, which is a property of the contour: in the Bank's networks they do, and on the local
 * stack a developer submitting a test message would otherwise need a client-credentials token to see a
 * single SMS leave.
 *
 * <p>mTLS is deliberately gone. It was never offered to the panel — a certificate identifies a machine,
 * and the panel needs to know which employee is looking — and for source systems it was a mechanism
 * without a CA, whose empty subject allowlist meant "any trusted certificate names its own stream".
 *
 * @param requireSourceSystemToken whether {@code /api/v1} refuses an unauthenticated call; the admin BFF
 *     is unaffected and always requires a token
 * @param streamClaim claim carrying the stream ids a source system may submit for; SEC-01 asks that a
 *     stream sees only its own data, and this is where "its own" is written down
 * @param rolesClaim claim carrying the SSO groups of an admin user; mapped onto the roles of §10.1
 *     ({@code app_role}) and checked on the backend, never on the client (FR-7.2, SEC-02)
 * @param anonymousMetrics whether {@code /actuator/prometheus} may be scraped without credentials;
 *     true is the normal deployment, where the management port is reachable only from the monitoring
 *     namespace, and false is for contours that expose it more widely
 * @param managementBasePath mirror of {@code management.endpoints.web.base-path}; kept here so the
 *     security rules do not drag the actuator's own configuration classes into this module
 */
@ConfigurationProperties("commhub.security")
public record SecurityProperties(
        boolean requireSourceSystemToken,
        String streamClaim,
        String rolesClaim,
        String scopeClaim,
        boolean anonymousMetrics,
        String managementBasePath) {

    public static final String DEFAULT_STREAM_CLAIM = "commhub_streams";

    public static final String DEFAULT_ROLES_CLAIM = "groups";

    public static final String DEFAULT_SCOPE_CLAIM = "scope";

    public static final String DEFAULT_MANAGEMENT_BASE_PATH = "/actuator";

    /** Value of the stream claim that entitles a caller to every stream; operations tooling only. */
    public static final String ALL_STREAMS = "*";

    public SecurityProperties {
        streamClaim = blankTo(streamClaim, DEFAULT_STREAM_CLAIM);
        rolesClaim = blankTo(rolesClaim, DEFAULT_ROLES_CLAIM);
        scopeClaim = blankTo(scopeClaim, DEFAULT_SCOPE_CLAIM);
        managementBasePath = blankTo(managementBasePath, DEFAULT_MANAGEMENT_BASE_PATH);
    }

    /** Defaults with source systems left open, which is what the local stack and the tests run with. */
    public static SecurityProperties disabled() {
        return new SecurityProperties(false, null, null, null, true, null);
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
