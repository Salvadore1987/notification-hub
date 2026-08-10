package uz.hamkorbank.commhub.adapter.out.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import uz.hamkorbank.commhub.adapter.out.provider.FixedClock;
import uz.hamkorbank.commhub.application.port.out.ClockPort;

/** Credentials come from the environment, never from the database or a log line (SEC-04, SG-04). */
class SecretResolverAdapterTest {

    private static final String SERVICE_ACCOUNT = "{\"type\":\"service_account\",\"project_id\":\"hb\"}";

    @Test
    @DisplayName("SEC-04: env: reads an environment variable, which is how the contour delivers a credential")
    void resolvesEnvironmentVariable() {
        // Arrange
        SecretResolverAdapter resolver = resolver(Map.of("PLAYMOBILE_PASSWORD", "s3cr3t\n"), Map.of());

        // Act
        String value = resolver.require("env:PLAYMOBILE_PASSWORD");

        // Assert
        assertThat(value).isEqualTo("s3cr3t");
    }

    @Test
    @DisplayName("SEC-04: env:base64: decodes the blob, which is how a .p8 key survives an environment variable")
    void decodesDeclaredBase64() {
        // Arrange: a key encoded on the command line arrives line-wrapped.
        String encoded = base64("-----BEGIN PRIVATE KEY-----\nMIGT\n-----END PRIVATE KEY-----");
        SecretResolverAdapter resolver = resolver(Map.of("APNS_PRIVATE_KEY", encoded + "\n"), Map.of());

        // Act
        String value = resolver.require("env:base64:APNS_PRIVATE_KEY");

        // Assert
        assertThat(value).startsWith("-----BEGIN PRIVATE KEY-----").endsWith("-----END PRIVATE KEY-----");
    }

    @Test
    @DisplayName("SEC-04: a base64: reference whose value is not base64 resolves to nothing, not to itself")
    void refusesDeclaredBase64ThatIsNot() {
        // Arrange
        SecretResolverAdapter resolver = resolver(Map.of("FCM_SERVICE_ACCOUNT", "not base64 at all!"), Map.of());

        // Act + Assert
        assertThat(resolver.resolve("env:base64:FCM_SERVICE_ACCOUNT")).isEmpty();
    }

    @Test
    @DisplayName("SEC-04: a base64-encoded blob is decoded even without the modifier")
    void decodesDetectedBase64Blob() {
        // Arrange
        SecretResolverAdapter resolver = resolver(Map.of("FCM_SERVICE_ACCOUNT", base64(SERVICE_ACCOUNT)), Map.of());

        // Act + Assert
        assertThat(resolver.resolve("env:FCM_SERVICE_ACCOUNT")).contains(SERVICE_ACCOUNT);
    }

    @Test
    @DisplayName("SEC-04: auto-detection leaves an ordinary password alone, base64-shaped or not")
    void leavesPasswordsAlone() {
        // Arrange: eight canonical base64 characters — decodable, but not into a blob.
        SecretResolverAdapter resolver = resolver(Map.of("SMSGATE_KEY", "aGFta29y"), Map.of());

        // Act + Assert
        assertThat(resolver.resolve("env:SMSGATE_KEY")).contains("aGFta29y");
    }

    @Test
    @DisplayName("SEC-04: a JSON blob supplied verbatim is used as it is")
    void leavesVerbatimBlobAlone() {
        // Arrange
        SecretResolverAdapter resolver = resolver(Map.of("FCM_SERVICE_ACCOUNT", SERVICE_ACCOUNT), Map.of());

        // Act + Assert
        assertThat(resolver.resolve("env:FCM_SERVICE_ACCOUNT")).contains(SERVICE_ACCOUNT);
    }

    @Test
    @DisplayName("SEC-04: the prop: scheme reads a property, which is how the local stack supplies values")
    void resolvesPropertyScheme() {
        // Arrange
        MockEnvironment environment = new MockEnvironment().withProperty("playmobile.password", "local");
        SecretResolverAdapter resolver = new SecretResolverAdapter(
                SecretProperties.defaults(), environment, name -> null, FixedClock.standard());

        // Act + Assert
        assertThat(resolver.resolve("prop:playmobile.password")).contains("local");
    }

    @Test
    @DisplayName("SEC-04: a literal from commhub.secrets.values is the fallback for tests and the local stack")
    void resolvesConfiguredLiteral() {
        // Arrange
        SecretResolverAdapter resolver = resolver(Map.of(), Map.of("smsgate/key", "abc"));

        // Act + Assert
        assertThat(resolver.resolve("smsgate/key")).contains("abc");
    }

    @Test
    @DisplayName("SEC-04: an environment variable never loses to a property of the same name")
    void environmentIsNotSpringProperty() {
        // Arrange: the yaml key would win if env went through Spring's Environment.
        MockEnvironment environment = new MockEnvironment().withProperty("PLAYMOBILE_PASSWORD", "from-yaml");
        SecretResolverAdapter resolver = new SecretResolverAdapter(
                SecretProperties.defaults(),
                environment,
                Map.of("PLAYMOBILE_PASSWORD", "from-env")::get,
                FixedClock.standard());

        // Act + Assert
        assertThat(resolver.resolve("env:PLAYMOBILE_PASSWORD")).contains("from-env");
    }

    @Test
    @DisplayName("SEC-04: a missing secret fails the call instead of sending with a blank credential")
    void requireFailsOnMissingSecret() {
        // Arrange
        SecretResolverAdapter resolver = resolver(Map.of(), Map.of());

        // Act + Assert
        assertThatThrownBy(() -> resolver.require("env:PLAYMOBILE_PASSWORD"))
                .isInstanceOf(SecretNotFoundException.class)
                .hasMessageContaining("env:PLAYMOBILE_PASSWORD");
    }

    @Test
    @DisplayName("SEC-04: a resolved value is reused for the cache window and looked up again after it")
    void cacheWindowBoundsTheLookup() {
        // Arrange
        Map<String, String> variables = new HashMap<>(Map.of("SMSGATE_KEY", "old"));
        MutableClock clock = new MutableClock(FixedClock.DEFAULT);
        SecretResolverAdapter resolver = new SecretResolverAdapter(
                new SecretProperties(Map.of(), Duration.ofSeconds(30)), new MockEnvironment(), variables::get, clock);
        assertThat(resolver.resolve("env:SMSGATE_KEY")).contains("old");
        variables.put("SMSGATE_KEY", "new");

        // Act
        String withinWindow = resolver.require("env:SMSGATE_KEY");
        clock.advanceSeconds(31);
        String afterWindow = resolver.require("env:SMSGATE_KEY");

        // Assert
        assertThat(withinWindow).isEqualTo("old");
        assertThat(afterWindow).isEqualTo("new");
    }

    private static SecretResolverAdapter resolver(Map<String, String> variables, Map<String, String> values) {
        return new SecretResolverAdapter(
                new SecretProperties(values, null), new MockEnvironment(), variables::get, FixedClock.standard());
    }

    private static String base64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    /** A clock the test moves by hand, to cross the cache window without waiting for it. */
    private static final class MutableClock implements ClockPort {

        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        private void advanceSeconds(long seconds) {
            now = now.plusSeconds(seconds);
        }

        @Override
        public Instant now() {
            return now;
        }

        @Override
        public ZoneId zone() {
            return ZoneId.of("Asia/Tashkent");
        }
    }
}
