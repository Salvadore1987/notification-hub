package uz.hamkorbank.commhub.adapter.in.rest.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import uz.hamkorbank.commhub.domain.model.type.ActorType;

/** SEC-01: a stream sees only its own data, and who "its own" is comes from the token. */
class StreamAccessGuardTest {

    private static final SecurityProperties ENABLED = new SecurityProperties(true, null, null, null, true, null);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("a client entitled to a stream may use it")
    void allowsTheEntitledStream() {
        // Arrange
        authenticate(jwt("crm-client", Map.of(SecurityProperties.DEFAULT_STREAM_CLAIM, List.of("crm", "chakana"))));

        // Act + Assert
        assertThatCode(() -> guard().check("chakana")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a client asking for someone else's stream is refused, and the refusal names it in the log only")
    void refusesForeignStreams() {
        // Arrange
        authenticate(jwt("crm-client", Map.of(SecurityProperties.DEFAULT_STREAM_CLAIM, List.of("crm"))));

        // Act + Assert
        assertThatThrownBy(() -> guard().check("chakana"))
                .isInstanceOf(StreamAccessDeniedException.class)
                .extracting(e -> ((StreamAccessDeniedException) e).streamId())
                .isEqualTo("chakana");
    }

    @Test
    @DisplayName("a token without the claim is entitled to nothing: missing configuration refuses, not permits")
    void refusesWhenTheClaimIsAbsent() {
        // Arrange
        authenticate(jwt("crm-client", Map.of()));

        // Act + Assert
        assertThatThrownBy(() -> guard().check("crm")).isInstanceOf(StreamAccessDeniedException.class);
    }

    @Test
    @DisplayName("the wildcard entitlement is for operations tooling and covers every stream")
    void allowsTheWildcardEntitlement() {
        // Arrange
        authenticate(
                jwt("ops", Map.of(SecurityProperties.DEFAULT_STREAM_CLAIM, List.of(SecurityProperties.ALL_STREAMS))));

        // Act + Assert
        assertThatCode(() -> guard().check("anything")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("with source-system tokens not required no stream is refused — ingest must not stop")
    void permitsEveryStreamWhenSourceSystemsAreOpen() {
        // Arrange
        StreamAccessGuard guard = new StreamAccessGuard(new AuthenticatedCaller(SecurityProperties.disabled()));

        // Act + Assert
        assertThatCode(() -> guard.check("chakana")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a role, unlike a stream, is refused to an unauthenticated caller whatever the contour")
    void refusesEveryRoleWithoutAuthentication() {
        // Arrange
        AuthenticatedCaller open = new AuthenticatedCaller(SecurityProperties.disabled());
        AuthenticatedCaller closed = new AuthenticatedCaller(ENABLED);

        // Act + Assert
        assertThat(open.hasAnyRole(Roles.ADMIN, Roles.VIEWER)).isFalse();
        assertThat(closed.hasAnyRole(Roles.ADMIN, Roles.VIEWER)).isFalse();
    }

    @Test
    @DisplayName("SEC-08: an operator is named by preferred_username, and by the subject when there is none")
    void namesTheOperatorByPreferredUsername() {
        // Arrange
        AuthenticatedCaller caller = new AuthenticatedCaller(ENABLED);

        // Act + Assert
        authenticate(jwt(
                "8f1c-uuid",
                Map.of(SecurityProperties.DEFAULT_ROLES_CLAIM, List.of("ADMIN"), "preferred_username", "i.petrov")));
        assertThat(caller.actor().id()).isEqualTo("i.petrov");

        authenticate(jwt("8f1c-uuid", Map.of(SecurityProperties.DEFAULT_ROLES_CLAIM, List.of("ADMIN"))));
        assertThat(caller.actor().id()).isEqualTo("8f1c-uuid");
    }

    @Test
    @DisplayName("SEC-08: a machine client is a source system, a token with SSO groups is a person")
    void distinguishesMachinesFromPeople() {
        // Arrange
        AuthenticatedCaller caller = new AuthenticatedCaller(ENABLED);
        authenticate(jwt("crm-client", Map.of(SecurityProperties.DEFAULT_STREAM_CLAIM, List.of("crm"))));

        // Act + Assert
        assertThat(caller.actor().type()).isEqualTo(ActorType.SOURCE_SYSTEM);
        assertThat(caller.actor().id()).isEqualTo("crm");

        authenticate(jwt("i.petrov", Map.of(SecurityProperties.DEFAULT_ROLES_CLAIM, List.of("OPERATOR"))));
        assertThat(caller.actor().type()).isEqualTo(ActorType.OPERATOR);
        assertThat(caller.actor().id()).isEqualTo("i.petrov");
    }

    @Test
    @DisplayName("without a principal the actor is the Hub itself, unless the caller declared one")
    void fallsBackToTheDeclaredActor() {
        // Arrange
        AuthenticatedCaller caller = new AuthenticatedCaller(SecurityProperties.disabled());

        // Act + Assert
        assertThat(caller.actorOf(null).type()).isEqualTo(ActorType.SYSTEM);
        assertThat(caller.actorOf("  ").type()).isEqualTo(ActorType.SYSTEM);
        assertThat(caller.actorOf("night-batch").id()).isEqualTo("night-batch");
    }

    private static StreamAccessGuard guard() {
        return new StreamAccessGuard(new AuthenticatedCaller(ENABLED));
    }

    private static void authenticate(org.springframework.security.core.Authentication authentication) {
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private static JwtAuthenticationToken jwt(String subject, Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "RS256").subject(subject);
        claims.forEach(builder::claim);
        return new JwtAuthenticationToken(builder.build(), Set.of(), subject);
    }
}
