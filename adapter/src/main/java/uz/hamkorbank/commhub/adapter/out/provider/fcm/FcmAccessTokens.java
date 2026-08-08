package uz.hamkorbank.commhub.adapter.out.provider.fcm;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import uz.hamkorbank.commhub.adapter.out.provider.support.Masking;
import uz.hamkorbank.commhub.adapter.out.provider.support.ProviderCallException;
import uz.hamkorbank.commhub.adapter.out.provider.support.ProviderHttpResponse;
import uz.hamkorbank.commhub.adapter.out.provider.support.ProviderRestClients;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * OAuth2 access tokens for FCM HTTP v1, refreshed on their own (PU-01).
 *
 * <p>The flow is the service-account one: a short JWT signed with the account's private key is
 * exchanged at Google's token endpoint for a bearer token valid for an hour. Everything here exists
 * because that hour ends: the token is cached and renewed a few minutes early, so the refresh happens
 * between messages rather than during one.
 *
 * <p>Implemented directly rather than through the Google auth library, which would pull in a transport
 * stack of its own — and the whole of it, for one signed assertion and one form post. The signature is
 * {@code RS256} from the JDK, the request is two form fields; what the library would add is a
 * dependency whose HTTP client the Hub cannot give its own timeouts (PR-01).
 *
 * <p>One token per service account, guarded by an {@link AtomicReference}: under a bulk fan-out several
 * virtual threads reach an expiring token at once, and the worst case is two exchanges, not a torn
 * value. A failed exchange throws {@link ProviderCallException}, so it is retried and counted by the
 * executor like any other call that produced no answer (PR-01).
 */
public class FcmAccessTokens {

    private static final Logger LOG = LoggerFactory.getLogger(FcmAccessTokens.class);

    /** Lifetime of the signed assertion; Google accepts up to an hour, and it is used within seconds. */
    private static final Duration ASSERTION_LIFETIME = Duration.ofMinutes(10);

    private static final String GRANT_TYPE = "urn:ietf:params:oauth:grant-type:jwt-bearer";

    private static final int LOGGED_BODY_LENGTH = 200;

    private final RestClient client;
    private final Duration refreshSkew;
    private final AtomicReference<CachedToken> cached = new AtomicReference<>();

    public FcmAccessTokens(ProviderRestClients clients, String tokenUrl, Duration refreshSkew) {
        Guard.notNull(clients, "clients");
        Guard.notBlank(tokenUrl, "tokenUrl");
        this.refreshSkew = Guard.notNull(refreshSkew, "refreshSkew");
        this.client = clients.create(
                uz.hamkorbank.commhub.adapter.out.provider.support.ProviderHttpProperties.of(originOf(tokenUrl)));
    }

    /** A valid bearer token for this account, exchanging one if the cached token is spent. */
    public String tokenFor(FcmServiceAccount account, Instant now) {
        Guard.notNull(account, "account");
        CachedToken token = cached.get();
        if (token != null && token.isUsable(account.clientEmail(), now, refreshSkew)) {
            return token.value();
        }
        CachedToken fresh = exchange(account, now);
        cached.set(fresh);
        return fresh.value();
    }

    /**
     * Drops the cached token (PU-04).
     *
     * <p>Called when Google refuses the credentials: the next message must not repeat a token that has
     * already been rejected, and an hour is a long time to keep refusing traffic over a revoked one.
     */
    public void invalidate() {
        cached.set(null);
    }

    private CachedToken exchange(FcmServiceAccount account, Instant now) {
        String assertion = assertion(account, now);
        ProviderHttpResponse response = ProviderRestClients.send(client.post()
                .uri(URI.create(account.tokenUrl()))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body("grant_type=%s&assertion=%s"
                        .formatted(
                                java.net.URLEncoder.encode(GRANT_TYPE, StandardCharsets.UTF_8),
                                java.net.URLEncoder.encode(assertion, StandardCharsets.UTF_8))));
        if (!response.isSuccess()) {
            throw failure(response);
        }
        FcmJson json = new FcmJson();
        JsonNode body = json.readOrNull(response.body());
        String accessToken = FcmJson.scalar(body, "access_token")
                .orElseThrow(() -> ProviderCallException.transport(
                        "the FCM token endpoint answered without an access_token", null));
        long expiresIn = FcmJson.scalar(body, "expires_in")
                .map(FcmAccessTokens::parseSeconds)
                .orElse(3600L);
        LOG.debug("FCM access token obtained for {}, valid for {}s", account.clientEmail(), expiresIn);
        return new CachedToken(account.clientEmail(), accessToken, now.plusSeconds(expiresIn));
    }

    /**
     * Google refused the exchange.
     *
     * <p>{@code 4xx} is blocking, not retryable: a revoked key, a disabled account or a wrong audience
     * does not improve with the next call, and every message that keeps trying is a message that could
     * have failed over to the other push provider instead (PR-01, FR-2.2). {@code 5xx} is Google's own
     * problem and is retried.
     */
    private static ProviderCallException failure(ProviderHttpResponse response) {
        String detail = "the FCM token endpoint answered %d: %s"
                .formatted(response.status(), Masking.body(response.body(), LOGGED_BODY_LENGTH));
        return response.isServerError()
                ? ProviderCallException.retryable(String.valueOf(response.status()), detail)
                : ProviderCallException.blocking(String.valueOf(response.status()), detail);
    }

    /** The signed JWT assertion of the service-account flow (PU-01). */
    private static String assertion(FcmServiceAccount account, Instant now) {
        String header = base64("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String claims = base64(("""
                {"iss":"%s","scope":"%s","aud":"%s","iat":%d,"exp":%d}""".formatted(
                                account.clientEmail(),
                                FcmProperties.OAuth.SCOPE,
                                account.tokenUrl(),
                                now.getEpochSecond(),
                                now.plus(ASSERTION_LIFETIME).getEpochSecond()))
                .getBytes(StandardCharsets.UTF_8));
        String signingInput = header + '.' + claims;
        return signingInput + '.' + base64(sign(account, signingInput));
    }

    private static byte[] sign(FcmServiceAccount account, String signingInput) {
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(account.privateKey());
            signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signature.sign();
        } catch (GeneralSecurityException e) {
            throw ProviderCallException.blocking(
                    "BAD_SERVICE_ACCOUNT", "the FCM assertion could not be signed: " + e.getMessage());
        }
    }

    private static String base64(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static long parseSeconds(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 3600L;
        }
    }

    /** Scheme and authority of the token URL; the path travels with the request itself. */
    private static String originOf(String tokenUrl) {
        URI uri = URI.create(tokenUrl);
        Guard.isTrue(uri.getHost() != null, "commhub.provider.fcm.oauth.token-url must be an absolute URL");
        return uri.getPort() < 0
                ? "%s://%s".formatted(uri.getScheme(), uri.getHost())
                : "%s://%s:%d".formatted(uri.getScheme(), uri.getHost(), uri.getPort());
    }

    /** A token and the moment it stops being one. */
    private record CachedToken(String clientEmail, String value, Instant expiresAt) {

        private boolean isUsable(String account, Instant now, Duration skew) {
            return clientEmail.equals(account) && now.plus(skew).isBefore(expiresAt);
        }
    }
}
