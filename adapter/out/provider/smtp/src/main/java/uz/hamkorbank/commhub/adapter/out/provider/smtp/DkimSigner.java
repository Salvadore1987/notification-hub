package uz.hamkorbank.commhub.adapter.out.provider.smtp;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import uz.hamkorbank.commhub.adapter.out.provider.support.Blobs;
import uz.hamkorbank.commhub.application.port.out.ClockPort;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * DKIM signature of an outgoing message (EM-03, RFC 6376).
 *
 * <p>Off unless the Bank asks for it. Deliverability — SPF, DKIM, DMARC — is the mail team's ground and a
 * corporate relay normally signs everything that leaves it; the Hub signs only when it talks to a relay that
 * does not, or when the Bank wants its notifications under their own selector.
 *
 * <p>{@code relaxed/relaxed} with {@code rsa-sha256}, which is what every verifier supports and what
 * survives the one thing that reliably happens to a bank's mail on its way out: a gateway that refolds a
 * header or re-wraps a line. The strict canonicalisation would break on exactly that.
 *
 * <p>The signature is computed over the message as it will be written, not over the model that produced it:
 * the serialised bytes are split into headers and body, and both halves are canonicalised from there. Anything
 * else signs a message that is subtly not the one sent — a different fold, a different boundary — and the
 * failure shows up as a verification error at the recipient, where nobody can debug it.
 *
 * <p>The private key comes from the secret store as PKCS#8 PEM and is never logged, never configured inline
 * and never written anywhere (SEC-04). It is parsed once per rotation, not once per message.
 */
public class DkimSigner {

    public static final String HEADER = "DKIM-Signature";

    private static final String ALGORITHM = "rsa-sha256";
    private static final String CANONICALIZATION = "relaxed/relaxed";
    private static final String CRLF = "\r\n";

    private final SmtpProperties.Dkim settings;
    private final ClockPort clock;
    private final AtomicReference<CachedKey> key = new AtomicReference<>();

    public DkimSigner(SmtpProperties.Dkim settings, ClockPort clock) {
        this.settings = Guard.notNull(settings, "settings");
        this.clock = Guard.notNull(clock, "clock");
    }

    public boolean isEnabled() {
        return settings.enabled();
    }

    /**
     * Adds the signature header to a message that is otherwise finished.
     *
     * @throws MessagingException when the message cannot be serialised or the key cannot be used; the caller
     *     turns that into a provider failure, because an unsigned message is not an acceptable fallback —
     *     DMARC would reject it at the recipient, and the Hub would count it as delivered
     */
    public void sign(MimeMessage message) throws MessagingException {
        Guard.notNull(message, "message");
        byte[] serialized = SmtpMessageCodec.serialize(message);
        try {
            String header = signature(serialized, settings, privateKey(), clock.now());
            // Никакого saveChanges() следом: он пересобирает Content-Transfer-Encoding, а тело уже
            // захешировано — подпись перестала бы сходиться ровно у получателя, где её не отладить.
            message.addHeaderLine(HEADER + ": " + header);
        } catch (GeneralSecurityException e) {
            throw new MessagingException("the message could not be DKIM-signed (EM-03): " + e.getMessage(), e);
        }
    }

    /** Drops the parsed key; the next signature re-reads it from the secret store (SEC-04). */
    public void invalidateKey() {
        key.set(null);
    }

    /**
     * Builds the value of the {@code DKIM-Signature} header (RFC 6376 §3.5).
     *
     * <p>Static and free of the container so that a test can hand it a message and a generated key pair and
     * verify the result the way a recipient would.
     */
    public static String signature(
            byte[] serializedMessage, SmtpProperties.Dkim settings, PrivateKey privateKey, Instant signedAt)
            throws GeneralSecurityException {
        Split split = Split.of(serializedMessage);
        Map<String, String> headers = split.headers();
        List<String> signed =
                settings.headers().stream().filter(headers::containsKey).toList();
        String bodyHash = Base64.getEncoder()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(canonicalizeBody(split.body())));
        String unsigned = "v=1; a=%s; c=%s; d=%s; s=%s; t=%d; h=%s; bh=%s; b="
                .formatted(
                        ALGORITHM,
                        CANONICALIZATION,
                        settings.domain(),
                        settings.selector(),
                        signedAt.getEpochSecond(),
                        String.join(":", signed),
                        bodyHash);
        StringBuilder input = new StringBuilder();
        for (String name : signed) {
            input.append(canonicalizeHeader(name, headers.get(name))).append(CRLF);
        }
        // Сам заголовок подписи входит в хеш с пустым b= и без завершающего CRLF (RFC 6376 §3.7).
        input.append(canonicalizeHeader(HEADER, unsigned));
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(privateKey);
        signature.update(input.toString().getBytes(StandardCharsets.UTF_8));
        return unsigned + Base64.getEncoder().encodeToString(signature.sign());
    }

    /**
     * Relaxed header canonicalisation (RFC 6376 §3.4.2): lower-cased name, unfolded value, runs of
     * whitespace collapsed to one space, no whitespace around the colon or at the end.
     */
    static String canonicalizeHeader(String name, String value) {
        return name.toLowerCase(Locale.ROOT) + ":"
                + collapseWhitespace(value.replace("\r\n", " ")).trim();
    }

    /**
     * Relaxed body canonicalisation (RFC 6376 §3.4.4): whitespace collapsed inside a line, stripped at the
     * end of a line, and trailing empty lines removed. A non-empty body always ends with exactly one CRLF; an
     * empty one canonicalises to nothing at all.
     */
    static byte[] canonicalizeBody(byte[] body) {
        String text = new String(body, StandardCharsets.UTF_8).replace("\r\n", "\n");
        StringBuilder canonical = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            canonical.append(stripTrailingWhitespace(collapseWhitespace(line))).append(CRLF);
        }
        while (canonical.length() >= 2 && canonical.lastIndexOf(CRLF + CRLF) == canonical.length() - 4) {
            canonical.setLength(canonical.length() - 2);
        }
        String result = canonical.toString();
        if (CRLF.equals(result)) {
            result = "";
        }
        return result.getBytes(StandardCharsets.UTF_8);
    }

    /** Parses the PKCS#8 PEM the secret store holds (SEC-04). */
    static PrivateKey parsePrivateKey(String pem) throws GeneralSecurityException {
        String base64 = pem.replaceAll("-----(BEGIN|END)[^-]*-----", "").replaceAll("\\s", "");
        if (base64.isEmpty()) {
            throw new GeneralSecurityException("the DKIM private key is empty");
        }
        try {
            return KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
        } catch (IllegalArgumentException e) {
            throw new GeneralSecurityException(
                    "the DKIM private key is not base64 PKCS#8; convert it with `openssl pkcs8 -topk8`", e);
        }
    }

    private PrivateKey privateKey() throws GeneralSecurityException {
        String pem = Blobs.decodeIfBase64(settings.privateKey());
        CachedKey cached = key.get();
        if (cached != null && cached.pem().equals(pem)) {
            return cached.key();
        }
        PrivateKey parsed = parsePrivateKey(pem);
        key.set(new CachedKey(pem, parsed));
        return parsed;
    }

    private static String collapseWhitespace(String value) {
        return value.replaceAll("[ \t]+", " ");
    }

    private static String stripTrailingWhitespace(String value) {
        int end = value.length();
        while (end > 0 && (value.charAt(end - 1) == ' ' || value.charAt(end - 1) == '\t')) {
            end--;
        }
        return value.substring(0, end);
    }

    /**
     * The parsed key and the PEM it came from.
     *
     * <p>Keyed by the PEM rather than by time: the secret resolver already re-reads the reference on its own
     * TTL, so a rotated key arrives here as different text and re-parses itself (SEC-04).
     */
    private record CachedKey(String pem, PrivateKey key) {}

    /**
     * A serialised message split into its headers and its body.
     *
     * <p>Header values keep their folding — canonicalisation unfolds them — and a header appearing more than
     * once keeps its last occurrence, which is the one a verifier reads (RFC 6376 §5.4.2).
     */
    record Split(Map<String, String> headers, byte[] body) {

        static Split of(byte[] message) {
            String text = new String(message, StandardCharsets.UTF_8);
            int separator = text.indexOf(CRLF + CRLF);
            String headerBlock = separator < 0 ? text : text.substring(0, separator);
            byte[] body =
                    separator < 0 ? new byte[0] : text.substring(separator + 4).getBytes(StandardCharsets.UTF_8);
            return new Split(parseHeaders(headerBlock), body);
        }

        private static Map<String, String> parseHeaders(String headerBlock) {
            Map<String, String> headers = new LinkedHashMap<>();
            List<String> unfolded = new ArrayList<>();
            for (String line : headerBlock.split(CRLF, -1)) {
                if (!unfolded.isEmpty() && !line.isEmpty() && (line.charAt(0) == ' ' || line.charAt(0) == '\t')) {
                    unfolded.set(unfolded.size() - 1, unfolded.getLast() + CRLF + line);
                } else if (!line.isBlank()) {
                    unfolded.add(line);
                }
            }
            for (String line : unfolded) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1));
                }
            }
            return headers;
        }
    }
}
