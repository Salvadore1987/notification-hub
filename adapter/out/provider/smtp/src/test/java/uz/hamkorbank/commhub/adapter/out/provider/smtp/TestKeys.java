package uz.hamkorbank.commhub.adapter.out.provider.smtp;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * A DKIM key pair for the tests, generated at class load rather than checked in.
 *
 * <p>A private key committed to a repository is a private key, whatever the comment above it says, and the
 * Bank's own scanners would be right to flag it (SEC-04).
 */
final class TestKeys {

    static final KeyPair DKIM_KEY_PAIR = generate();

    static final String DKIM_PRIVATE_KEY_PEM = "-----BEGIN PRIVATE KEY-----\n"
            + Base64.getMimeEncoder().encodeToString(DKIM_KEY_PAIR.getPrivate().getEncoded())
            + "\n-----END PRIVATE KEY-----\n";

    private TestKeys() {}

    private static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("RSA is not available in this JVM", e);
        }
    }
}
