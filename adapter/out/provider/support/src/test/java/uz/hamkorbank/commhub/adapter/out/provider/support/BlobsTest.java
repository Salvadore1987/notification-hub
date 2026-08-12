package uz.hamkorbank.commhub.adapter.out.provider.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** How a multi-line credential survives an environment variable (ADR-0044). */
class BlobsTest {

    private static final String SERVICE_ACCOUNT =
            "{\n  \"type\": \"service_account\",\n  \"project_id\": \"hamkor\"\n}";

    private static final String PRIVATE_KEY = "-----BEGIN PRIVATE KEY-----\nMIGT\nAgEA\n-----END PRIVATE KEY-----";

    @Test
    @DisplayName("PU-01: a base64 service account key comes back as its JSON")
    void decodesBase64Json() {
        // Arrange
        String encoded = encode(SERVICE_ACCOUNT);

        // Act
        String decoded = Blobs.decodeIfBase64(encoded);

        // Assert
        assertThat(decoded).isEqualTo(SERVICE_ACCOUNT.strip());
    }

    @Test
    @DisplayName("PU-06: a line-wrapped base64 .p8 comes back as its PEM")
    void decodesLineWrappedBase64Pem() {
        // Arrange — `base64` on the command line wraps at 76 characters.
        String encoded = wrap(encode(PRIVATE_KEY));

        // Act
        String decoded = Blobs.decodeIfBase64(encoded);

        // Assert
        assertThat(decoded).isEqualTo(PRIVATE_KEY);
    }

    @Test
    @DisplayName("a value already in the clear is left alone")
    void keepsClearValues() {
        // Act + Assert
        assertThat(Blobs.decodeIfBase64(SERVICE_ACCOUNT)).isEqualTo(SERVICE_ACCOUNT);
        assertThat(Blobs.decodeIfBase64(PRIVATE_KEY)).isEqualTo(PRIVATE_KEY);
    }

    @Test
    @DisplayName("a password that happens to be valid base64 is not decoded")
    void keepsPasswordsThatLookLikeBase64() {
        // Arrange — "hamkor" encoded is itself a legal password, and the rule must not touch it.
        String password = encode("hamkor");

        // Act + Assert
        assertThat(Blobs.decodeIfBase64(password)).isEqualTo(password);
        assertThat(Blobs.decodeIfBase64("s3cr3t")).isEqualTo("s3cr3t");
    }

    @Test
    @DisplayName("nothing is invented for a value that is not there")
    void passesEmptyValuesThrough() {
        // Act + Assert
        assertThat(Blobs.decodeIfBase64(null)).isNull();
        assertThat(Blobs.decodeIfBase64("")).isEmpty();
        assertThat(Blobs.decodeIfBase64("   ")).isEqualTo("   ");
    }

    private static String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String wrap(String encoded) {
        StringBuilder wrapped = new StringBuilder();
        for (int start = 0; start < encoded.length(); start += 8) {
            wrapped.append(encoded, start, Math.min(start + 8, encoded.length()))
                    .append('\n');
        }
        return wrapped.toString();
    }
}
