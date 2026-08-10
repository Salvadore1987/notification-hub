package uz.hamkorbank.commhub.adapter.out.secret;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where credentials come from and how long a resolved one is reused (SEC-04, SG-04).
 *
 * <p>A reference names its source: {@code env:NAME} is an environment variable — the way the Bank's
 * contour delivers credentials — and {@code prop:key} a Spring property. Either may carry a
 * {@code base64:} modifier ({@code env:base64:FCM_SERVICE_ACCOUNT}) for a multi-line blob. A reference
 * without a scheme is looked up among {@link #values()}, which exists for the local stack and the
 * tests and is never used in the Bank's contour.
 *
 * <p>Vault is not talked to directly. The platform puts the secret into the pod's environment (a
 * Kubernetes secret, a Vault agent injector, a CSI driver); an adapter that authenticated to Vault
 * itself would add a second credential to protect and put Vault on the path of every provider call.
 *
 * @param values fallback literals for the local stack and tests — never used in the Bank's contour
 * @param cacheTtl how long a resolved value is reused; it keeps a per-message provider call away from
 *     the lookup and bounds how fast a changed {@code prop:} value applies. An environment variable
 *     cannot change inside a running process — rotating one is a rolling restart (see ADR-0036).
 */
@ConfigurationProperties("commhub.secrets")
public record SecretProperties(Map<String, String> values, Duration cacheTtl) {

    public static final Duration DEFAULT_CACHE_TTL = Duration.ofSeconds(30);

    public SecretProperties {
        values = values == null ? Map.of() : Map.copyOf(values);
        cacheTtl = cacheTtl == null || cacheTtl.isNegative() ? DEFAULT_CACHE_TTL : cacheTtl;
    }

    public static SecretProperties defaults() {
        return new SecretProperties(null, null);
    }
}
