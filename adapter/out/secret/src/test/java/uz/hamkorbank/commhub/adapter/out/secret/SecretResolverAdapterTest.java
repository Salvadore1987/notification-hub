package uz.hamkorbank.commhub.adapter.out.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import uz.hamkorbank.commhub.adapter.out.provider.FixedClock;
import uz.hamkorbank.commhub.application.port.out.ClockPort;

/** Credentials come from the platform, never from the database or a log line (SEC-04, SG-04). */
class SecretResolverAdapterTest {

    @Test
    @DisplayName("SEC-04: a scheme-less reference is a file in the mounted secret directory")
    void resolvesMountedFile(@TempDir Path mount) throws IOException {
        // Arrange
        Files.writeString(mount.resolve("playmobile-password"), "s3cr3t\n", StandardCharsets.UTF_8);
        SecretResolverAdapter resolver = resolver(mount, Map.of());

        // Act
        String value = resolver.require("playmobile-password");

        // Assert
        assertThat(value).isEqualTo("s3cr3t");
    }

    @Test
    @DisplayName("SEC-04: the prop: scheme reads a property, which is how the local stack supplies values")
    void resolvesPropertyScheme() {
        // Arrange
        MockEnvironment environment = new MockEnvironment().withProperty("playmobile.password", "local");
        SecretResolverAdapter resolver =
                new SecretResolverAdapter(SecretProperties.defaults(), environment, FixedClock.standard());

        // Act + Assert
        assertThat(resolver.resolve("prop:playmobile.password")).contains("local");
    }

    @Test
    @DisplayName("SEC-04: a literal from commhub.secrets.values is the fallback for tests and the local stack")
    void resolvesConfiguredLiteral() {
        // Arrange
        SecretResolverAdapter resolver = resolver(null, Map.of("smsgate/key", "abc"));

        // Act + Assert
        assertThat(resolver.resolve("smsgate/key")).contains("abc");
    }

    @Test
    @DisplayName("SEC-04: a reference escaping the secret directory resolves to nothing")
    void refusesPathTraversal(@TempDir Path mount) throws IOException {
        // Arrange: the reference comes from the provider table, which the admin panel writes.
        Path outside = mount.getParent().resolve("outside-secret");
        Files.writeString(outside, "leaked", StandardCharsets.UTF_8);
        SecretResolverAdapter resolver = resolver(mount, Map.of());

        // Act + Assert
        assertThat(resolver.resolve("../outside-secret")).isEmpty();
    }

    @Test
    @DisplayName("SEC-04: a missing secret fails the call instead of sending with a blank credential")
    void requireFailsOnMissingSecret() {
        // Arrange
        SecretResolverAdapter resolver = resolver(null, Map.of());

        // Act + Assert
        assertThatThrownBy(() -> resolver.require("playmobile/password"))
                .isInstanceOf(SecretNotFoundException.class)
                .hasMessageContaining("playmobile/password");
    }

    @Test
    @DisplayName("SEC-04: a rotated secret is picked up once the cache window has passed, without a restart")
    void rotationAppliesAfterTheCacheWindow(@TempDir Path mount) throws IOException {
        // Arrange
        Path secret = mount.resolve("smsgate-key");
        Files.writeString(secret, "old", StandardCharsets.UTF_8);
        Instant start = FixedClock.DEFAULT;
        MutableClock clock = new MutableClock(start);
        SecretResolverAdapter resolver = new SecretResolverAdapter(
                new SecretProperties(mount.toString(), Map.of(), Duration.ofSeconds(30)), new MockEnvironment(), clock);
        assertThat(resolver.resolve("smsgate-key")).contains("old");
        Files.writeString(secret, "new", StandardCharsets.UTF_8);

        // Act
        String withinWindow = resolver.require("smsgate-key");
        clock.advanceSeconds(31);
        String afterWindow = resolver.require("smsgate-key");

        // Assert
        assertThat(withinWindow).isEqualTo("old");
        assertThat(afterWindow).isEqualTo("new");
    }

    private static SecretResolverAdapter resolver(Path directory, Map<String, String> values) {
        return new SecretResolverAdapter(
                new SecretProperties(directory == null ? null : directory.toString(), values, null),
                new MockEnvironment(),
                FixedClock.standard());
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
