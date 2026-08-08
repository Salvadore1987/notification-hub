package uz.hamkorbank.commhub.adapter.in.callback;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import uz.hamkorbank.commhub.adapter.in.callback.CallbackProperties.Provider;
import uz.hamkorbank.commhub.application.port.out.SecretResolverPort;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Decides whether a callback really comes from the provider it names (PM-02, SEC-07).
 *
 * <p>Two independent checks, both configured per provider: the source address has to be on the
 * allowlist agreed with that provider, and the shared secret has to match. Either can be switched off
 * — some contours terminate the allowlist in the network layer — but switching both off is a
 * deliberate act an operator performs, not something a missing property does quietly.
 *
 * <p>The secret is compared in constant time. A webhook is reachable from outside and answers
 * predictably, which is exactly the setting where a byte-by-byte comparison leaks the secret one
 * character at a time.
 *
 * <p>Not a servlet filter: a filter would have to re-parse the path to learn which provider is being
 * addressed and would run for every request in the application. This runs once, inside the one
 * controller that needs it, with the provider already resolved.
 */
@Component
public class CallbackGuard {

    private static final Logger LOG = LoggerFactory.getLogger(CallbackGuard.class);

    /** Headers a reverse proxy uses to pass the original client address, in order of preference. */
    private static final List<String> FORWARDED_FOR_HEADERS = List.of("X-Forwarded-For", "X-Real-IP");

    private final CallbackProperties properties;
    private final SecretResolverPort secrets;

    public CallbackGuard(CallbackProperties properties, SecretResolverPort secrets) {
        this.properties = Guard.notNull(properties, "properties");
        this.secrets = Guard.notNull(secrets, "secrets");
    }

    /**
     * @throws CallbackAuthenticationException when the caller cannot be accepted as this provider
     */
    public void check(String providerCode, HttpServletRequest request) {
        Provider provider = properties
                .providerOf(providerCode)
                .orElseThrow(() -> new CallbackAuthenticationException(
                        providerCode, "no callback configuration for provider " + providerCode));
        if (!provider.enabled()) {
            throw new CallbackAuthenticationException(providerCode, "callbacks are disabled for " + providerCode);
        }
        checkAddress(providerCode, provider, request);
        checkSecret(providerCode, provider, request);
    }

    /**
     * The value the header is expected to carry (SG-04).
     *
     * <p>A reference wins over a literal: in the Bank's contour the secret is agreed with the provider
     * and stored in the secret store, and the literal exists only so the local stack works without one.
     * A reference that resolves to nothing fails the callback rather than falling through to an empty
     * comparison — a guard that silently stops guarding is worse than one that is loudly broken.
     */
    private String expectedSecret(String providerCode, Provider provider) {
        if (!provider.hasSecretRef()) {
            return provider.secret();
        }
        return secrets.resolve(provider.secretRef())
                .orElseThrow(() -> new CallbackAuthenticationException(
                        providerCode, "the configured callback secret reference resolves to nothing"));
    }

    private static void checkAddress(String providerCode, Provider provider, HttpServletRequest request) {
        if (!provider.checksAddress()) {
            return;
        }
        String address = callerAddress(request);
        if (!provider.allowedIps().contains(address)) {
            LOG.warn("Callback for {} refused: address {} is not on the allowlist", providerCode, address);
            throw new CallbackAuthenticationException(providerCode, "address " + address + " is not allowed");
        }
    }

    private void checkSecret(String providerCode, Provider provider, HttpServletRequest request) {
        if (!provider.checksSecret()) {
            return;
        }
        String presented = request.getHeader(provider.secretHeader());
        if (presented == null || !constantTimeEquals(presented, expectedSecret(providerCode, provider))) {
            LOG.warn("Callback for {} refused: the shared secret does not match", providerCode);
            throw new CallbackAuthenticationException(providerCode, "the shared secret does not match");
        }
    }

    /**
     * The address the request came from, honouring the proxy headers of the Bank's contour.
     *
     * <p>{@code X-Forwarded-For} accumulates hops; the first entry is the original client, and it is
     * only trustworthy because nothing outside the contour reaches this endpoint directly.
     */
    private static String callerAddress(HttpServletRequest request) {
        for (String header : FORWARDED_FOR_HEADERS) {
            String value = request.getHeader(header);
            if (value != null && !value.isBlank()) {
                return value.split(",")[0].trim();
            }
        }
        return request.getRemoteAddr();
    }

    private static boolean constantTimeEquals(String presented, String expected) {
        return MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }
}
