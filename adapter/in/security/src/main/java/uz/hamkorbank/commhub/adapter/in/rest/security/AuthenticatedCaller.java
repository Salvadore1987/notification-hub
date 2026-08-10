package uz.hamkorbank.commhub.adapter.in.rest.security;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import uz.hamkorbank.commhub.domain.model.Actor;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Who is making this call, in the terms the Hub records things in (SEC-01, SEC-03, FR-7.3).
 *
 * <p>One place turns a Spring Security principal into the two answers the adapters need: the domain
 * {@link Actor} that ends up in the status history and the audit journal, and the set of streams the
 * caller may act for. Both are read from the authentication and nothing else — a header naming an actor
 * is a claim the caller makes about itself, and once SEC-01 is deployed the token is the only thing that
 * decides.
 *
 * <p>The stream entitlement comes from a claim ({@code commhub_streams} by default). A caller with the
 * wildcard entitlement — operations tooling, the admin BFF — is treated as entitled to every stream; a
 * caller with no claim at all is entitled to none, which is what makes the absence of configuration a
 * refusal rather than a hole.
 *
 * <p>Nobody authenticated is not the same answer everywhere, and the difference is deliberate. A
 * <em>role</em> is refused (there is no such thing as an anonymous operator, and the admin chain is
 * authenticated on every contour anyway), while a <em>stream</em> follows
 * {@link SecurityProperties#requireSourceSystemToken()} — a contour that has not switched SEC-01 on for
 * its source systems has no way of naming their streams, and refusing them would stop ingest rather
 * than protect it.
 */
@Component
public class AuthenticatedCaller {

    /** OIDC standard claim carrying a person's login; what an audit entry should name (SEC-08). */
    private static final String PREFERRED_USERNAME_CLAIM = "preferred_username";

    private final SecurityProperties properties;

    public AuthenticatedCaller(SecurityProperties properties) {
        this.properties = Guard.notNull(properties, "properties");
    }

    /** Name of the authenticated caller: {@code preferred_username} for a person, the client id otherwise. */
    public Optional<String> name() {
        return authentication().map(Authentication::getName);
    }

    /**
     * Who this call is, in domain terms (FR-7.3, SEC-08).
     *
     * <p>A person and a machine are not the same actor, and the difference decides whether a lookup is
     * journalled as access to personal data: a token carrying SSO groups is somebody from the admin
     * panel, and a client-credentials token carrying only a stream entitlement is a source system.
     * Nobody authenticated is the Hub.
     */
    public Actor actor() {
        Optional<Authentication> authentication = authentication();
        if (authentication.isEmpty()) {
            return Actor.system();
        }
        Authentication caller = authentication.get();
        if (caller.getPrincipal() instanceof Jwt jwt
                && !claimAsList(jwt, properties.rolesClaim()).isEmpty()) {
            return Actor.operator(operatorName(jwt, caller));
        }
        return allowedStreams(caller).stream()
                .filter(stream -> !SecurityProperties.ALL_STREAMS.equals(stream))
                .findFirst()
                .map(Actor::sourceSystem)
                .orElseGet(() -> Actor.operator(caller.getName()));
    }

    /**
     * Login of the employee behind this token, for the audit journal (SEC-08).
     *
     * <p>{@code preferred_username} rather than the token's subject: SEC-08 asks who looked at a
     * customer's message, and an SSO UUID does not answer that without a second lookup in a directory
     * the Hub has no access to. The subject remains the fallback, because an IdP is free not to mint the
     * claim and a journal entry naming somebody obscurely still beats one naming nobody.
     */
    private static String operatorName(Jwt jwt, Authentication caller) {
        String preferred = jwt.getClaimAsString(PREFERRED_USERNAME_CLAIM);
        return preferred == null || preferred.isBlank() ? caller.getName() : preferred;
    }

    /**
     * Actor to record for an action performed over this call (FR-7.3).
     *
     * @param declaredActor value of the {@code X-Commhub-Actor} header; used only while nobody is
     *     authenticated, i.e. before SEC-01 is switched on in a contour
     */
    public Actor actorOf(String declaredActor) {
        if (authentication().isPresent()) {
            return actor();
        }
        return declaredActor == null || declaredActor.isBlank() ? Actor.system() : Actor.operator(declaredActor.trim());
    }

    /**
     * Whether this caller holds one of the given RBAC roles (SEC-03, FR-7.2).
     *
     * <p>Used where the answer is a <em>degree</em> rather than a yes or no — how much of an address a
     * screen shows (§11.2 "Сообщения") — and never as a substitute for the {@code @PreAuthorize} that
     * decides whether the endpoint may be called at all. Those two must not be the same mechanism: one
     * refuses a request, the other shapes a response, and collapsing them is how an endpoint ends up
     * with authorisation expressed only in what it happens to render.
     *
     * <p>With nobody authenticated the answer is no. A role is a statement about a person, and there is
     * no anonymous person: the endpoints that ask this question all sit behind the admin chain, which
     * authenticates on every contour, so the only way to reach here unauthenticated is a bug — and the
     * safe answer to a bug is the narrowest one (an over-masked address, not an unmasked one).
     */
    public boolean hasAnyRole(String... roles) {
        Optional<Authentication> authentication = authentication();
        if (authentication.isEmpty()) {
            return false;
        }
        Set<String> held = authentication.get().getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return Arrays.stream(roles).map(Roles::authority).anyMatch(held::contains);
    }

    /** Whether this caller may submit for, and read, the given stream (SEC-01). */
    public boolean mayUseStream(String streamId) {
        Optional<Authentication> authentication = authentication();
        if (authentication.isEmpty()) {
            return !properties.requireSourceSystemToken();
        }
        Set<String> allowed = allowedStreams(authentication.get());
        return allowed.contains(SecurityProperties.ALL_STREAMS) || allowed.contains(streamId);
    }

    /** Streams named by the token claim; a principal that is not a token names none. */
    private Set<String> allowedStreams(Authentication authentication) {
        Set<String> streams = new LinkedHashSet<>();
        if (authentication.getPrincipal() instanceof Jwt jwt) {
            streams.addAll(claimAsList(jwt, properties.streamClaim()));
        }
        return streams;
    }

    private static List<String> claimAsList(Jwt jwt, String claim) {
        Object value = jwt.getClaim(claim);
        return switch (value) {
            case null -> List.of();
            case String single -> List.of(single.split("[\\s,]+"));
            case Collection<?> many -> many.stream().map(String::valueOf).toList();
            default -> List.of(String.valueOf(value));
        };
    }

    private static Optional<Authentication> authentication() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || isAnonymous(authentication)) {
            return Optional.empty();
        }
        return Optional.of(authentication);
    }

    private static boolean isAnonymous(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ANONYMOUS".equals(authority.getAuthority()));
    }
}
