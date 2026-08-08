package uz.hamkorbank.commhub.adapter.in.rest.security;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import uz.hamkorbank.commhub.adapter.in.admin.AdminApi;
import uz.hamkorbank.commhub.adapter.in.callback.CallbackProperties;
import uz.hamkorbank.commhub.adapter.in.rest.ApiV1;

/**
 * Who may reach which endpoint (SEC-01, SEC-02, SEC-03, SEC-07).
 *
 * <p>Five chains, ordered, because the kinds of caller have nothing in common. Provider callbacks
 * authenticate themselves against a shared secret and an address allowlist inside the controller
 * (SEC-07) — an OAuth2 token from Playmobile is not a thing that exists. The management endpoints are
 * scraped by the platform. The admin BFF takes an OIDC token carrying SSO groups and checks a role at
 * every endpoint (SEC-02, SEC-03). Source systems present a client-credentials token or a client
 * certificate. Everything else is refused rather than left to a default nobody reviewed.
 *
 * <p>All chains are stateless and have CSRF disabled: there is no browser session here — the admin SPA
 * authenticates with a bearer token of its own, which is not a credential a browser attaches
 * automatically and therefore not something a cross-site request can borrow.
 *
 * <p><strong>Authentication is not on by default.</strong> The local stack has no issuer and no CA, and
 * a default that requires a token would mean every developer switching it off — the state in which it
 * would then be deployed. Instead an instance without either mechanism logs a warning naming what is
 * missing, and the Bank's deployment turns on what its standard prescribes (NF-06).
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

    public WebSecurityConfig(SecurityProperties properties) {
        if (!properties.isAuthenticationRequired()) {
            LOG.warn("Source-system authentication is disabled: neither commhub.security.oauth2-enabled nor "
                    + "commhub.security.mtls-enabled is set. SEC-01 expects one of them in the Bank's contour.");
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
     * the {@code @PreAuthorize} of each endpoint checks. mTLS is deliberately not offered here: a
     * certificate identifies a machine, and the panel needs to know which employee is looking.
     *
     * <p>Authenticated at the chain and authorised at the method, and both are needed. The chain answers
     * 401 to a caller with no token at all; the method answers 403 to one whose roles do not cover the
     * endpoint. Collapsing them would make "who are you" and "may you" the same answer, which is the
     * difference between a login prompt and a permissions request.
     *
     * <p>With no issuer configured this chain permits everything and the roles cannot be evaluated —
     * {@code AdminAccess} logs a warning saying so at startup, the same stance SEC-01 takes for source
     * systems.
     */
    @Bean
    @Order(3)
    public SecurityFilterChain adminSecurityFilterChain(HttpSecurity http, SecurityProperties properties)
            throws Exception {
        stateless(http.securityMatcher(ADMIN_BASE_PATH + "/**")).authorizeHttpRequests(requests -> {
            if (properties.oauth2Enabled()) {
                requests.anyRequest().authenticated();
            } else {
                requests.anyRequest().permitAll();
            }
        });
        return applyAuthentication(http, properties).build();
    }

    /** The API of the source systems: token or certificate, and the stream check of SEC-01 on top. */
    @Bean
    @Order(4)
    public SecurityFilterChain sourceSystemSecurityFilterChain(HttpSecurity http, SecurityProperties properties)
            throws Exception {
        stateless(http.securityMatcher(ApiV1.BASE + "/**")).authorizeHttpRequests(requests -> {
            if (properties.isAuthenticationRequired()) {
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
            if (properties.isAuthenticationRequired()) {
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
     * Adds the mechanisms this deployment enabled; both may be on at once.
     *
     * <p>OAuth2 needs a {@code JwtDecoder}, which Boot builds from
     * {@code spring.security.oauth2.resourceserver.jwt.issuer-uri}. Enabling OAuth2 without an issuer
     * fails at startup on purpose: an instance that believes it is authenticating and is not is worse
     * than one that refuses to start.
     */
    private HttpSecurity applyAuthentication(HttpSecurity http, SecurityProperties properties) throws Exception {
        if (properties.oauth2Enabled()) {
            http.oauth2ResourceServer(oauth2 ->
                    oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter(properties))));
        }
        if (properties.mtlsEnabled()) {
            http.x509(x509 -> x509.subjectPrincipalRegex("CN=([^,]*)")
                    .userDetailsService(certificateUserDetailsService(properties)));
        }
        return http;
    }

    /**
     * Claims → authorities (SEC-03).
     *
     * <p>Two families and they are not interchangeable: OAuth2 scopes describe what a machine client may
     * call ({@code SCOPE_messages.write}), SSO groups describe what a person is ({@code ROLE_OPERATOR}).
     * The group claim is mapped onto the roles {@code app_role} seeds in §10.1, so the backend decides
     * and the SPA of UI-02 only hides what the backend would refuse anyway.
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

    /**
     * mTLS clients (SEC-01), and the reason no local account exists.
     *
     * <p>The certificate subject is the identity, and by convention its CN <em>is</em> the stream id —
     * which is what lets {@link StreamAccessGuard} answer without a second registry to keep in step.
     * Subjects outside the configured allowlist are refused here rather than at the stream check, so an
     * unknown certificate never reaches an endpoint at all.
     *
     * <p>Declared as a bean even when mTLS is off, because its presence is what stops Spring Boot from
     * generating a default user and printing its password into the log of every start (§10.1: users come
     * from SSO, the Hub stores no passwords).
     */
    @Bean
    public UserDetailsService certificateUserDetailsService(SecurityProperties properties) {
        return subject -> {
            if (!properties.mtlsEnabled() || !properties.permitsSubject(subject)) {
                throw new UsernameNotFoundException("client certificate subject is not allowed");
            }
            return User.withUsername(subject)
                    .password("")
                    .authorities(List.of(new SimpleGrantedAuthority(Roles.authority(Roles.OPERATOR))))
                    .build();
        };
    }
}
