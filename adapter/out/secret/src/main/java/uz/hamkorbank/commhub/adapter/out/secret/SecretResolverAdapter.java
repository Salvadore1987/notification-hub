package uz.hamkorbank.commhub.adapter.out.secret;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import uz.hamkorbank.commhub.application.port.out.ClockPort;
import uz.hamkorbank.commhub.application.port.out.SecretResolverPort;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Reads provider and stream credentials from the process environment (SEC-04, SG-04, PR-03, ADR-0036).
 *
 * <p>The only place in the Hub that ever holds a credential value. Everything else carries a
 * reference: {@code Provider.credentialsRef} in the database, {@code …-ref} properties in the provider
 * adapters. Nothing resolved here is ever logged, put on an exception message or written to the
 * database — a failure names the reference, never what was found under it.
 *
 * <p>A reference names its source: {@code env:NAME} (the contour), {@code prop:key} (a Spring
 * property), or no scheme at all, which is a literal from {@code commhub.secrets.values} and exists
 * for the local stack and the tests. Either scheme may carry a {@code base64:} modifier for a
 * multi-line blob — the FCM service account and the APNs {@code .p8} key are why it exists.
 *
 * <p>Values are cached for {@link SecretProperties#cacheTtl()}, which keeps a per-message provider
 * call away from the lookup. It is no longer what makes a rotation apply: an environment variable
 * cannot change inside a running process, so rotating one is a rolling restart of the deployment.
 */
@Component
public class SecretResolverAdapter implements SecretResolverPort {

    private static final Logger LOG = LoggerFactory.getLogger(SecretResolverAdapter.class);

    private static final String ENV_SCHEME = "env:";
    private static final String PROPERTY_SCHEME = "prop:";

    /** Modifier after the scheme: the value is Base64 and has to be decoded before it is used. */
    private static final String BASE64_MODIFIER = "base64:";

    /** Property namespace a scheme-less reference falls back to; mirrors {@code commhub.secrets.values}. */
    private static final String VALUES_PREFIX = "commhub.secrets.values.";

    /** Canonical Base64, no line breaks: what the auto-detection below is willing to consider. */
    private static final Pattern STRICT_BASE64 = Pattern.compile("[A-Za-z0-9+/]+={0,2}");

    private final SecretProperties properties;
    private final Environment environment;
    private final EnvironmentVariables variables;
    private final ClockPort clock;
    private final Map<String, CachedSecret> cache = new ConcurrentHashMap<>();

    public SecretResolverAdapter(
            SecretProperties properties, Environment environment, EnvironmentVariables variables, ClockPort clock) {
        this.properties = Guard.notNull(properties, "properties");
        this.environment = Guard.notNull(environment, "environment");
        this.variables = Guard.notNull(variables, "variables");
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
            return read(ref, ref.substring(ENV_SCHEME.length()), variables::get);
        }
        if (ref.startsWith(PROPERTY_SCHEME)) {
            return read(ref, ref.substring(PROPERTY_SCHEME.length()), environment::getProperty);
        }
        return nonBlank(properties.values().get(ref))
                .or(() -> nonBlank(environment.getProperty(VALUES_PREFIX + ref)))
                .map(SecretResolverAdapter::decodeBlobIfBase64);
    }

    /**
     * One source, one lookup, with the {@code base64:} modifier applied if the reference carries it.
     *
     * @param ref the whole reference, for the log line a failure produces — never the value
     * @param locator what follows the scheme: the variable name or the property key, possibly prefixed
     */
    private static Optional<String> read(String ref, String locator, UnaryOperator<String> source) {
        if (locator.startsWith(BASE64_MODIFIER)) {
            return nonBlank(source.apply(locator.substring(BASE64_MODIFIER.length())))
                    .flatMap(value -> decodeBase64(value, ref));
        }
        return nonBlank(source.apply(locator)).map(SecretResolverAdapter::decodeBlobIfBase64);
    }

    /**
     * The value of a reference that asked for {@code base64:} explicitly.
     *
     * <p>Whitespace is dropped first: a key encoded with {@code base64} on the command line arrives
     * line-wrapped. A value that is not Base64 at all resolves to nothing rather than to itself — the
     * deployment said what the encoding is, and sending with a mangled credential is worse than not
     * sending.
     */
    private static Optional<String> decodeBase64(String value, String ref) {
        String compact = value.replaceAll("\\s", "");
        try {
            return utf8(Base64.getDecoder().decode(compact));
        } catch (IllegalArgumentException standard) {
            try {
                return utf8(Base64.getUrlDecoder().decode(compact));
            } catch (IllegalArgumentException url) {
                LOG.warn("Secret reference {} is declared base64 but its value is not; it is ignored", ref);
                return Optional.empty();
            }
        }
    }

    /**
     * Decodes a value that was not declared Base64 but plainly is one.
     *
     * <p>Narrow on purpose: the value has to be canonical Base64 without whitespace, decode to valid
     * UTF-8, and yield a blob marker — an opening brace for JSON, {@code -----BEGIN} for PEM — that it
     * did not already start with. A password cannot be mangled by that rule short of a coincidence,
     * and a deployment that wants certainty writes {@code base64:} — the modifier is never guessed at,
     * only added to.
     */
    private static String decodeBlobIfBase64(String value) {
        if (looksLikeBlob(value)
                || value.length() % 4 != 0
                || !STRICT_BASE64.matcher(value).matches()) {
            return value;
        }
        try {
            return utf8(Base64.getDecoder().decode(value))
                    .filter(SecretResolverAdapter::looksLikeBlob)
                    .orElse(value);
        } catch (IllegalArgumentException notBase64) {
            return value;
        }
    }

    private static boolean looksLikeBlob(String value) {
        String text = value.stripLeading();
        return text.startsWith("{") || text.startsWith("-----BEGIN");
    }

    /** The bytes as text, or nothing if they are not UTF-8 — a binary secret is not one we can carry. */
    private static Optional<String> utf8(byte[] bytes) {
        CharsetDecoder decoder = StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            CharBuffer decoded = decoder.decode(ByteBuffer.wrap(bytes));
            return nonBlank(decoded.toString());
        } catch (CharacterCodingException notText) {
            return Optional.empty();
        }
    }

    private static Optional<String> nonBlank(String value) {
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value.strip());
    }

    /**
     * One resolution attempt with its timestamp.
     *
     * <p>A miss is cached too: a provider whose credential reference is wrong would otherwise repeat
     * the lookup on every message it sends, and the answer will not change within the TTL either way.
     */
    private record CachedSecret(Optional<String> value, Instant resolvedAt) {

        boolean isFreshAt(Instant now, Duration ttl) {
            return now.isBefore(resolvedAt.plus(ttl));
        }
    }
}
