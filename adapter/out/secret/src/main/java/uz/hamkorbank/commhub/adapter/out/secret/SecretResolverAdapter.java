package uz.hamkorbank.commhub.adapter.out.secret;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import uz.hamkorbank.commhub.application.port.out.ClockPort;
import uz.hamkorbank.commhub.application.port.out.SecretResolverPort;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Reads provider and stream credentials from the platform's secret store (SEC-04, SG-04, PR-03).
 *
 * <p>The only place in the Hub that ever holds a credential value. Everything else carries a
 * reference: {@code Provider.credentialsRef} in the database, {@code …-ref} properties in the provider
 * adapters. Nothing resolved here is ever logged, put on an exception message or written to the
 * database — a failure names the reference, never what was found under it.
 *
 * <p>Values are cached for {@link SecretProperties#cacheTtl()} rather than for the lifetime of the
 * process. A rotation therefore applies without a restart, within that window (SEC-04): the file the
 * Vault agent or the kubelet rewrites is simply read again. Caching at all is what keeps a per-message
 * provider call from touching the filesystem.
 */
@Component
public class SecretResolverAdapter implements SecretResolverPort {

    private static final Logger LOG = LoggerFactory.getLogger(SecretResolverAdapter.class);

    private static final String ENV_SCHEME = "env:";
    private static final String FILE_SCHEME = "file:";
    private static final String PROPERTY_SCHEME = "prop:";

    /** Property namespace a scheme-less reference falls back to; mirrors {@code commhub.secrets.values}. */
    private static final String VALUES_PREFIX = "commhub.secrets.values.";

    private final SecretProperties properties;
    private final Environment environment;
    private final ClockPort clock;
    private final Map<String, CachedSecret> cache = new ConcurrentHashMap<>();

    public SecretResolverAdapter(SecretProperties properties, Environment environment, ClockPort clock) {
        this.properties = Guard.notNull(properties, "properties");
        this.environment = Guard.notNull(environment, "environment");
        this.clock = Guard.notNull(clock, "clock");
    }

    @Override
    public Optional<String> resolve(String secretRef) {
        if (secretRef == null || secretRef.isBlank()) {
            return Optional.empty();
        }
        String ref = secretRef.trim();
        Instant now = clock.now();
        CachedSecret cached = cache.get(ref);
        if (cached != null && cached.isFreshAt(now, properties.cacheTtl())) {
            return cached.value();
        }
        Optional<String> value = load(ref);
        cache.put(ref, new CachedSecret(value, now));
        return value;
    }

    @Override
    public String require(String secretRef) {
        return resolve(secretRef)
                .orElseThrow(() -> new SecretNotFoundException(secretRef == null ? "<null>" : secretRef.trim()));
    }

    /** Drops the cached values; used after an operator-driven rotation and by the tests. */
    public void evict() {
        cache.clear();
    }

    private Optional<String> load(String ref) {
        if (ref.startsWith(ENV_SCHEME)) {
            return nonBlank(System.getenv(ref.substring(ENV_SCHEME.length())));
        }
        if (ref.startsWith(FILE_SCHEME)) {
            return readFile(Path.of(ref.substring(FILE_SCHEME.length())), ref);
        }
        if (ref.startsWith(PROPERTY_SCHEME)) {
            return nonBlank(environment.getProperty(ref.substring(PROPERTY_SCHEME.length())));
        }
        return mountedFile(ref)
                .or(() -> nonBlank(properties.values().get(ref)))
                .or(() -> nonBlank(environment.getProperty(VALUES_PREFIX + ref)));
    }

    /**
     * A scheme-less reference as a file under the mounted secret directory.
     *
     * <p>The reference is checked to stay inside that directory: it arrives from the {@code provider}
     * table, which the admin panel writes, and a reference of {@code ../../etc/passwd} must read
     * nothing rather than something.
     */
    private Optional<String> mountedFile(String ref) {
        if (!properties.hasDirectory()) {
            return Optional.empty();
        }
        Path root = Path.of(properties.directory()).toAbsolutePath().normalize();
        Path candidate = root.resolve(ref).toAbsolutePath().normalize();
        if (!candidate.startsWith(root)) {
            LOG.warn("Secret reference {} points outside the secret directory and is ignored", ref);
            return Optional.empty();
        }
        return readFile(candidate, ref);
    }

    private static Optional<String> readFile(Path path, String ref) {
        if (!Files.isReadable(path)) {
            return Optional.empty();
        }
        try {
            return nonBlank(Files.readString(path, StandardCharsets.UTF_8).strip());
        } catch (IOException e) {
            // The reference, never the content: an I/O message can quote what it managed to read.
            LOG.warn(
                    "Secret reference {} could not be read: {}",
                    ref,
                    e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private static Optional<String> nonBlank(String value) {
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    /**
     * One resolution attempt with its timestamp.
     *
     * <p>A miss is cached too: a provider whose credential reference is wrong would otherwise hit the
     * filesystem on every message it sends, and the answer will not change within the TTL either way.
     */
    private record CachedSecret(Optional<String> value, Instant resolvedAt) {

        boolean isFreshAt(Instant now, Duration ttl) {
            return now.isBefore(resolvedAt.plus(ttl));
        }
    }
}
