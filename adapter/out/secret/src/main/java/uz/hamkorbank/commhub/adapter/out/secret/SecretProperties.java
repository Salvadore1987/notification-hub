package uz.hamkorbank.commhub.adapter.out.secret;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where credentials come from and how fast a rotation is picked up (SEC-04, SG-04).
 *
 * <p>Three sources, tried in the order a reference names them: {@code env:NAME}, {@code file:/path}
 * and {@code prop:key}. A reference without a scheme is looked up as a file under {@link #directory()}
 * and then among {@link #values()} — that is the shape of a Kubernetes secret mounted as a volume,
 * which is how the Bank's contour delivers them.
 *
 * <p>Vault is not talked to directly. In the target contour the Vault agent renders secrets into the
 * pod's mounted volume, so the application resolves files and never holds a Vault token; an adapter
 * that authenticated to Vault itself would add a second credential to protect and put Vault on the
 * path of every provider call.
 *
 * @param directory root of the mounted secrets; a reference without a scheme is a file name under it
 * @param values fallback literals for the local stack and tests — never used in the Bank's contour
 * @param cacheTtl how long a resolved value is reused; bounds how long a rotation takes to apply
 *     without a restart (SEC-04, AD-07 asks for ≤ 30 s)
 */
@ConfigurationProperties("commhub.secrets")
public record SecretProperties(String directory, Map<String, String> values, Duration cacheTtl) {

    public static final Duration DEFAULT_CACHE_TTL = Duration.ofSeconds(30);

    public SecretProperties {
        directory = directory == null || directory.isBlank() ? null : directory.trim();
        values = values == null ? Map.of() : Map.copyOf(values);
        cacheTtl = cacheTtl == null || cacheTtl.isNegative() ? DEFAULT_CACHE_TTL : cacheTtl;
    }

    public static SecretProperties defaults() {
        return new SecretProperties(null, null, null);
    }

    public boolean hasDirectory() {
        return directory != null;
    }
}
