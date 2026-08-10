package uz.hamkorbank.commhub.support;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * Access tokens of the demo realm, for the tests that have to be somebody (SEC-02, SEC-03).
 *
 * <p>The direct access grant (password flow) rather than a hand-signed JWT: the point of these tests is
 * that the group of a real user reaches {@code @PreAuthorize} as a role, and a token the test minted
 * itself would prove the converter agrees with the test rather than with Keycloak. The grant is enabled
 * on {@code commhub-admin} in the demo realm only — a browser client in the Bank's contour has no
 * business accepting passwords.
 *
 * <p>Tokens are cached per user: the realm's lifespan is fifteen minutes, which outlives any run, and
 * one round trip per role beats one per assertion.
 */
public final class KeycloakTokens {

    /** The demo administrator shipped with the realm; the same credentials a developer logs in with. */
    public static final String ADMIN_USER = "demo";

    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

    private static final RestClient CLIENT = RestClient.create();

    private KeycloakTokens() {}

    /** Token of the demo ADMIN. */
    public static String admin() {
        return tokenFor(ADMIN_USER, ADMIN_USER);
    }

    /** Token of a user in one group only; the realm gives every role a user whose password is its name. */
    public static String of(String username) {
        return tokenFor(username, username);
    }

    public static String tokenFor(String username, String password) {
        return CACHE.computeIfAbsent(username, ignored -> request(username, password));
    }

    private static String request(String username, String password) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "password");
        form.add("client_id", "commhub-admin");
        form.add("username", username);
        form.add("password", password);
        form.add("scope", "openid");

        // Keycloak answers a refusal with 400 and a body that says why ("Account is not fully set up"
        // when the realm export left a required action behind, for instance). The default handler
        // throws that body away, and the test then fails on the wrong line with the wrong reason.
        ResponseEntity<Map> response = CLIENT.post()
                .uri(HubTestContainers.issuerUri() + "/protocol/openid-connect/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .onStatus(status -> true, (request, ignored) -> {})
                .toEntity(Map.class);

        Map<?, ?> body = response.getBody();
        Object token = body == null ? null : body.get("access_token");
        if (token == null) {
            throw new IllegalStateException("Keycloak issued no token for " + username + " (HTTP "
                    + response.getStatusCode().value() + "): " + body);
        }
        return String.valueOf(token);
    }
}
