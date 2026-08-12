package uz.hamkorbank.commhub.adapter.out.provider.fcm;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import tools.jackson.databind.JsonNode;
import uz.hamkorbank.commhub.adapter.out.provider.support.ProviderCallException;

/**
 * The Google service account key, parsed (PU-01, SEC-04).
 *
 * <p>The key arrives as the JSON file Google hands out, in a variable of the pod's environment like
 * every other credential — never from the yaml, never from the image. Four of its fields matter:
 * the project the notifications are sent for, the account they are sent as, its private key, and the
 * token endpoint to exchange them at.
 *
 * <p>{@code project_id} is read from the key rather than configured beside it: two sources for the same
 * fact is one source too many, and the failure of the wrong pairing — every send refused for a project
 * the key has no access to — names neither of them.
 *
 * <p>{@link #toString()} renders nothing sensitive: this object travels through the adapter and a stray
 * {@code log.debug("{}", account)} would otherwise print the Bank's Firebase private key.
 */
public record FcmServiceAccount(String projectId, String clientEmail, PrivateKey privateKey, String tokenUrl) {

    private static final String PEM_HEADER = "-----BEGIN PRIVATE KEY-----";
    private static final String PEM_FOOTER = "-----END PRIVATE KEY-----";

    /**
     * Parses the service account JSON.
     *
     * @param fallbackTokenUrl endpoint used when the key does not name one
     * @throws ProviderCallException blocking, when the key is unusable — a malformed key is not a
     *     transient failure and every message would otherwise be retried against it (PR-01)
     */
    public static FcmServiceAccount parse(FcmJson json, String document, String fallbackTokenUrl) {
        JsonNode root = json.readOrNull(document);
        if (root == null) {
            throw ProviderCallException.blocking(
                    "BAD_SERVICE_ACCOUNT", "the FCM service account key is not valid JSON (PU-01)");
        }
        String projectId = FcmJson.scalar(root, "project_id")
                .orElseThrow(() -> ProviderCallException.blocking(
                        "BAD_SERVICE_ACCOUNT", "the FCM service account key has no project_id"));
        String clientEmail = FcmJson.scalar(root, "client_email")
                .orElseThrow(() -> ProviderCallException.blocking(
                        "BAD_SERVICE_ACCOUNT", "the FCM service account key has no client_email"));
        String privateKey = FcmJson.scalar(root, "private_key")
                .orElseThrow(() -> ProviderCallException.blocking(
                        "BAD_SERVICE_ACCOUNT", "the FCM service account key has no private_key"));
        String tokenUrl = FcmJson.scalar(root, "token_uri").orElse(fallbackTokenUrl);
        return new FcmServiceAccount(projectId, clientEmail, readPkcs8(privateKey), tokenUrl);
    }

    /** Path of {@code messages:send} for this key's project (PU-01). */
    public String sendPath() {
        return FcmProperties.SEND_PATH_TEMPLATE.formatted(projectId);
    }

    /** RSA private key from the PEM block Google embeds in the JSON key. */
    private static PrivateKey readPkcs8(String pem) {
        String base64 = pem.replace(PEM_HEADER, "").replace(PEM_FOOTER, "").replaceAll("\\s", "");
        try {
            byte[] der = Base64.getDecoder().decode(base64.getBytes(StandardCharsets.US_ASCII));
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (IllegalArgumentException | NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw ProviderCallException.blocking(
                    "BAD_SERVICE_ACCOUNT", "the private key of the FCM service account cannot be read: " + e);
        }
    }

    @Override
    public String toString() {
        return "FcmServiceAccount[projectId=%s, clientEmail=%s, privateKey=***]".formatted(projectId, clientEmail);
    }
}
