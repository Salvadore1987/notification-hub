package uz.hamkorbank.commhub.adapter.in.rest.security;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.security.core.Authentication;
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
 * <p>The stream entitlement comes from a claim ({@code commhub_streams} by default) for OAuth2 clients
 * and from the certificate subject for mTLS ones, where the convention is that the CN <em>is</em> the
 * stream id. A caller with the wildcard entitlement — operations tooling, the admin BFF — is treated as
 * entitled to every stream; a caller with no claim at all is entitled to none, which is what makes the
 * absence of configuration a refusal rather than a hole.
 *
 * <p>With authentication switched off there is no principal, and everything here answers "the system"
 * and "every stream". That is the local stack and the contours where the platform terminates identity in
 * front of the application; it is also why {@link SecurityProperties#isAuthenticationRequired()} is
 * logged at startup.
 */
@Component
public class AuthenticatedCaller {

    private final SecurityProperties properties;

    public AuthenticatedCaller(SecurityProperties properties) {
        this.properties = Guard.notNull(properties, "properties");
    }

    /** Name of the authenticated caller: the OAuth2 client id or subject, or the certificate CN. */
    public Optional<String> name() {
        return authentication().map(Authentication::getName);
    }

    /**
     * Who this call is, in domain terms (FR-7.3, SEC-08).
     *
     * <p>A person and a machine are not the same actor, and the difference decides whether a lookup is
     * journalled as access to personal data: a token carrying SSO groups is somebody from the admin
     * panel, a client-credentials token carrying only a stream entitlement is a source system, and a
     * client certificate is a source system whose CN names its stream. Nobody authenticated is the Hub.
     */
    public Actor actor() {
        Optional<Authentication> authentication = authentication();
        if (authentication.isEmpty()) {
            return Actor.system();
        }
        Authentication caller = authentication.get();
        if (caller.getPrincipal() instanceof Jwt jwt
                && !claimAsList(jwt, properties.rolesClaim()).isEmpty()) {
            return Actor.operator(caller.getName());
        }
        return allowedStreams(caller).stream()
                .filter(stream -> !SecurityProperties.ALL_STREAMS.equals(stream))
                .findFirst()
                .map(Actor::sourceSystem)
                .orElseGet(() -> Actor.operator(caller.getName()));
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

    /** Whether this caller may submit for, and read, the given stream (SEC-01). */
    public boolean mayUseStream(String streamId) {
        Optional<Authentication> authentication = authentication();
        if (authentication.isEmpty()) {
            return !properties.isAuthenticationRequired();
        }
        Set<String> allowed = allowedStreams(authentication.get());
        return allowed.contains(SecurityProperties.ALL_STREAMS) || allowed.contains(streamId);
    }

    /** Streams named by the token claim, or the certificate subject for an mTLS client. */
    private Set<String> allowedStreams(Authentication authentication) {
        Set<String> streams = new LinkedHashSet<>();
        if (authentication.getPrincipal() instanceof Jwt jwt) {
            streams.addAll(claimAsList(jwt, properties.streamClaim()));
        } else {
            streams.add(authentication.getName());
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
