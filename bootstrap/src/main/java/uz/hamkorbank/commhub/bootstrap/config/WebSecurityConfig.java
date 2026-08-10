package uz.hamkorbank.commhub.bootstrap.config;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import uz.hamkorbank.commhub.adapter.in.admin.AdminApi;
import uz.hamkorbank.commhub.adapter.in.callback.CallbackProperties;
import uz.hamkorbank.commhub.adapter.in.rest.ApiV1;
import uz.hamkorbank.commhub.adapter.in.rest.security.Roles;
import uz.hamkorbank.commhub.adapter.in.rest.security.SecurityProperties;

/**
 * Who may reach which endpoint (SEC-01, SEC-02, SEC-03, SEC-07).
 *
 * <p>Five chains, ordered, because the kinds of caller have nothing in common. Provider callbacks
 * authenticate themselves against a shared secret and an address allowlist inside the controller
 * (SEC-07) — an OAuth2 token from Playmobile is not a thing that exists. The management endpoints are
 * scraped by the platform. The admin BFF takes an OIDC token carrying SSO groups and checks a role at
 * every endpoint (SEC-02, SEC-03). Source systems present a client-credentials token. Everything else is
 * refused rather than left to a default nobody reviewed.
 *
 * <p>All chains are stateless and have CSRF disabled: there is no browser session here — the admin SPA
 * authenticates with a bearer token of its own, which is not a credential a browser attaches
 * automatically and therefore not something a cross-site request can borrow.
 *
 * <p><strong>The admin panel is behind OIDC on every contour</strong> (ADR-0037), so an issuer is
 * mandatory and its absence stops the instance rather than opening the panel. What a contour still
 * decides is whether <em>source systems</em> must present a token on {@code /api/v1}: the Bank's
 * networks say yes (NF-06), the local stack says no, and an instance that says no says so in the log.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class WebSecurityConfig {

    private static final Logger LOG = LoggerFactory.getLogger(WebSecurityConfig.class);

    /**
     * Base path of the admin BFF, taken from its own constant rather than repeated here.
     *
     * <p>A security matcher written as a literal is a security matcher that stops matching the day
     * somebody renames the path — quietly, and in the direction that leaves endpoints unprotected.
     */
    private static final String ADMIN_BASE_PATH = AdminApi.BASE;

    /**
     * @param issuerUri OIDC issuer, injected as a parameter rather than read from a field because
     *     {@code LayerConventionsTest} forbids field injection — and because a value this constructor
     *     refuses to start without has no business being set after the object exists
     */
    public WebSecurityConfig(
            SecurityProperties properties,
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:}") String issuerUri) {
        if (issuerUri == null || issuerUri.isBlank()) {
            throw new IllegalStateException("spring.security.oauth2.resourceserver.jwt.issuer-uri is required: "
                    + "the admin panel is behind OIDC on every contour (SEC-02, ADR-0037), and an instance "
                    + "with no issuer cannot validate a token. Set "
                    + "SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI; locally that is "
                    + "http://localhost:8180/realms/commhub after `docker compose up -d keycloak`.");
        }
        if (!properties.requireSourceSystemToken()) {
            LOG.warn("Source-system authentication is disabled: commhub.security.require-source-system-token "
                    + "is off, so /api/v1 accepts unauthenticated calls. SEC-01 expects a token in the Bank's "
                    + "contour; the admin panel is unaffected and stays behind OIDC.");
        }
    }

    /** Provider webhooks: guarded by {@code CallbackGuard}, never by a token (SEC-07, PM-02, SG-04). */
    @Bean
    @Order(1)
    public SecurityFilterChain callbackSecurityFilterChain(HttpSecurity http, CallbackProperties callbacks)
            throws Exception {
        return stateless(http.securityMatcher(callbacks.base() + "/**"))
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                .build();
    }

    /**
     * Management endpoints (NF-05, OBS-01).
     *
     * <p>The probes are open: a liveness probe that needs a credential is a pod the platform cannot
     * restart. Metrics follow {@code commhub.security.anonymous-metrics}, true for the normal deployment
     * where the management port is reachable only from the monitoring namespace. Everything else under
     * the actuator base path — the kill switch of Phase 14 among it — needs the ADMIN role.
     */
    @Bean
    @Order(2)
    public SecurityFilterChain managementSecurityFilterChain(HttpSecurity http, SecurityProperties properties)
            throws Exception {
        String base = properties.managementBasePath();
        stateless(http.securityMatcher(base + "/**")).authorizeHttpRequests(requests -> {
            requests.requestMatchers(base + "/health", base + "/health/**", base + "/info")
                    .permitAll();
            if (properties.anonymousMetrics()) {
                requests.requestMatchers(base + "/prometheus", base + "/metrics", base + "/metrics/**")
                        .permitAll();
            }
            requests.anyRequest().hasRole(Roles.ADMIN);
        });
        return applyAuthentication(http, properties).build();
    }

    /**
     * The admin BFF: a bearer token from the corporate SSO, and RBAC on top (SEC-02, SEC-03, UI-02).
     *
     * <p>Its own chain rather than a share of the source-system one, because the two callers have
     * nothing in common. A source system presents client credentials and is entitled to its streams; a
     * person presents an OIDC token carrying SSO groups, which map onto the roles of §10.1 and are what
     * the {@code @PreAuthorize} of each endpoint checks.
     *
     * <p>Authenticated at the chain and authorised at the method, and both are needed. The chain answers
     * 401 to a caller with no token at all; the method answers 403 to one whose roles do not cover the
     * endpoint. Collapsing them would make "who are you" and "may you" the same answer, which is the
     * difference between a login prompt and a permissions request.
     *
     * <p>Unconditional, and that is the whole of ADR-0037: there is no configuration under which this
     * chain lets an anonymous caller reach the kill switch, the routing configuration or a customer's
     * message. A contour that has not been given an issuer does not open the panel — it fails to start.
     */
    @Bean
    @Order(3)
    public SecurityFilterChain adminSecurityFilterChain(HttpSecurity http, SecurityProperties properties)
            throws Exception {
        stateless(http.securityMatcher(ADMIN_BASE_PATH + "/**"))
                .authorizeHttpRequests(requests -> requests.anyRequest().authenticated());
        return applyAuthentication(http, properties).build();
    }

    /** The API of the source systems: a client-credentials token, and the stream check of SEC-01 on top. */
    @Bean
    @Order(4)
    public SecurityFilterChain sourceSystemSecurityFilterChain(HttpSecurity http, SecurityProperties properties)
            throws Exception {
        stateless(http.securityMatcher(ApiV1.BASE + "/**")).authorizeHttpRequests(requests -> {
            if (properties.requireSourceSystemToken()) {
                requests.anyRequest().authenticated();
            } else {
                requests.anyRequest().permitAll();
            }
        });
        return applyAuthentication(http, properties).build();
    }

    /** Nothing else is published; a request that reaches here is refused rather than defaulted. */
    @Bean
    @Order(5)
    public SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http, SecurityProperties properties)
            throws Exception {
        stateless(http).authorizeHttpRequests(requests -> {
            if (properties.requireSourceSystemToken()) {
                requests.anyRequest().authenticated();
            } else {
                requests.anyRequest().permitAll();
            }
        });
        return applyAuthentication(http, properties).build();
    }

    private static HttpSecurity stateless(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
    }

    /**
     * Attaches the bearer-token mechanism, which every chain but the callbacks one uses.
     *
     * <p>Unconditional: the {@code JwtDecoder} Boot builds from
     * {@code spring.security.oauth2.resourceserver.jwt.issuer-uri} is always there, because the
     * constructor refuses to start without an issuer. Note that Boot's decoder is a lazy
     * {@code SupplierJwtDecoder} — discovery happens on the first token, not at startup — so an
     * unreachable issuer costs 401s and not a boot failure. That is a RUNBOOK symptom, not a bug.
     */
    private HttpSecurity applyAuthentication(HttpSecurity http, SecurityProperties properties) throws Exception {
        return http.oauth2ResourceServer(
                oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter(properties))));
    }

    /**
     * Claims → authorities (SEC-03).
     *
     * <p>Two families and they are not interchangeable: OAuth2 scopes describe what a machine client may
     * call ({@code SCOPE_messages.write}), SSO groups describe what a person is ({@code ROLE_OPERATOR}).
     * The group claim is mapped onto the roles {@code app_role} seeds in §10.1, so the backend decides
     * and the SPA of UI-02 only hides what the backend would refuse anyway.
     *
     * <p>The group value is used as written, upper-cased: a group has to be named after the role it
     * grants ({@code ADMIN}, not {@code /commhub-admin}), which is what the local realm export in
     * {@code docker/keycloak/} does and what the Bank's SSO mapping has to do too.
     *
     * <p>The principal claim is left at Spring's default {@code sub}. Naming an employee for the audit
     * journal is {@code AuthenticatedCaller}'s job instead, because {@code setPrincipalClaimName} has no
     * fallback — its principal answers the empty string for a token that lacks the claim, and a
     * client-credentials token from an IdP that does not mint {@code preferred_username} would then have
     * no name at all.
     */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter(SecurityProperties properties) {
        JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter();
        scopes.setAuthoritiesClaimName(properties.scopeClaim());
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> authoritiesOf(jwt, scopes, properties));
        return converter;
    }

    private static Collection<GrantedAuthority> authoritiesOf(
            Jwt jwt, JwtGrantedAuthoritiesConverter scopes, SecurityProperties properties) {
        Set<GrantedAuthority> authorities = new LinkedHashSet<>(scopes.convert(jwt));
        Object groups = jwt.getClaim(properties.rolesClaim());
        if (groups instanceof Collection<?> values) {
            values.stream()
                    .map(String::valueOf)
                    .map(group -> new SimpleGrantedAuthority(Roles.authority(group.toUpperCase(Locale.ROOT))))
                    .forEach(authorities::add);
        }
        return authorities;
    }
}
