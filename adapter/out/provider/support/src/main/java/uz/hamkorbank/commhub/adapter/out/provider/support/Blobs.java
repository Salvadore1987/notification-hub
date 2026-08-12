package uz.hamkorbank.commhub.adapter.out.provider.support;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A multi-line credential that travelled in an environment variable (ADR-0044).
 *
 * <p>Two of the Hub's credentials are documents rather than strings — the FCM service account key
 * (JSON) and the APNs {@code .p8} (PEM), and the DKIM key is a third — and both an environment
 * variable and a {@code .env} file carry them badly: docker-compose, CI runners and shells disagree
 * about line breaks, and a PEM that lost them fails at signing time with an error that names the
 * algorithm rather than the deployment. Base64 makes such a value one line, so a contour is free to
 * deliver it either way and this is the one place that tells them apart.
 *
 * <p>The rule is narrow on purpose: the value has to be Base64 (whitespace is dropped first — a key
 * encoded on the command line arrives line-wrapped), decode to valid UTF-8, and <em>yield a blob
 * marker it did not already carry</em> — an opening brace for JSON, {@code -----BEGIN} for PEM.
 * An ordinary password cannot be mangled by that rule short of a coincidence, because a password
 * that decodes into something starting with {@code -----BEGIN} is not a password.
 */
public final class Blobs {

    private static final Pattern STRICT_BASE64 = Pattern.compile("[A-Za-z0-9+/\\-_]+={0,2}");

    private Blobs() {}

    /**
     * The value as the adapter needs it: decoded when it plainly is a Base64 blob, verbatim otherwise.
     *
     * <p>Never fails and never returns nothing for a value that was given: a credential the Hub cannot
     * recognise is passed on unchanged, and it is the provider that gets to say it is wrong.
     */
    public static String decodeIfBase64(String value) {
        if (value == null || value.isBlank() || looksLikeBlob(value)) {
            return value;
        }
        String compact = value.replaceAll("\\s", "");
        if (compact.length() % 4 != 0 || !STRICT_BASE64.matcher(compact).matches()) {
            return value;
        }
        return decode(compact).filter(Blobs::looksLikeBlob).orElse(value);
    }

    private static Optional<String> decode(String compact) {
        try {
            return utf8(Base64.getDecoder().decode(compact));
        } catch (IllegalArgumentException standard) {
            try {
                return utf8(Base64.getUrlDecoder().decode(compact));
            } catch (IllegalArgumentException url) {
                return Optional.empty();
            }
        }
    }

    private static boolean looksLikeBlob(String value) {
        String text = value.stripLeading();
        return text.startsWith("{") || text.startsWith("-----BEGIN");
    }

    /** The bytes as text, or nothing if they are not UTF-8 — a binary credential is not one we carry. */
    private static Optional<String> utf8(byte[] bytes) {
        CharsetDecoder decoder = StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            CharBuffer decoded = decoder.decode(ByteBuffer.wrap(bytes));
            String text = decoded.toString();
            return text.isBlank() ? Optional.empty() : Optional.of(text.strip());
        } catch (CharacterCodingException notText) {
            return Optional.empty();
        }
    }
}
