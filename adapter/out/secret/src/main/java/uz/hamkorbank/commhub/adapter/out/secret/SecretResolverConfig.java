package uz.hamkorbank.commhub.adapter.out.secret;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the process environment as a collaborator rather than a static call (SEC-04, ADR-0036).
 *
 * <p>{@code System.getenv} cannot be set from a test, and since the environment became the primary
 * source of every credential it is the path that must not go untested. A bean is what keeps
 * {@link SecretResolverAdapter} on a single constructor — the project's injection rule leaves no room
 * for a second, test-only one.
 */
@Configuration
class SecretResolverConfig {

    @Bean
    EnvironmentVariables environmentVariables() {
        return System::getenv;
    }
}
