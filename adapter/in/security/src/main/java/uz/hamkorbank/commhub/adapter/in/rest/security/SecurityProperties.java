package uz.hamkorbank.commhub.adapter.in.rest.security;

import java.util.Locale;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How callers prove who they are and what that entitles them to (SEC-01, SEC-02, SEC-03).
 *
 * <p>Two mechanisms, independently switchable, because the Bank's standard allows either and some
 * contours use both: OAuth2 client credentials (a JWT the resource server validates against the issuer
 * of {@code spring.security.oauth2.resourceserver.jwt.issuer-uri}) and mutual TLS (a client certificate
 * terminated by the platform or by the JVM). Neither is on by default — the local stack has no issuer
 * and no CA — and an instance started with both off logs a warning at startup instead of pretending it
 * is protected.
 *
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
        boolean oauth2Enabled,
        boolean mtlsEnabled,
        String streamClaim,
        String rolesClaim,
        String scopeClaim,
        Set<String> mtlsAllowedSubjects,
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
        mtlsAllowedSubjects = mtlsAllowedSubjects == null ? Set.of() : Set.copyOf(mtlsAllowedSubjects);
    }

    public static SecurityProperties disabled() {
        return new SecurityProperties(false, false, null, null, null, null, true, null);
    }

    /** Whether any authentication at all is required of source systems. */
    public boolean isAuthenticationRequired() {
        return oauth2Enabled || mtlsEnabled;
    }

    /** Whether a client certificate with this subject may talk to the API; empty allowlist means any. */
    public boolean permitsSubject(String subject) {
        if (mtlsAllowedSubjects.isEmpty()) {
            return true;
        }
        String wanted = subject == null ? "" : subject.trim().toLowerCase(Locale.ROOT);
        return mtlsAllowedSubjects.stream()
                .anyMatch(allowed -> allowed.trim().toLowerCase(Locale.ROOT).equals(wanted));
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
