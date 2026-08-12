package uz.hamkorbank.commhub.adapter.out.provider.support;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uz.hamkorbank.commhub.adapter.out.persistence.config.ConfigurationCacheProperties;
import uz.hamkorbank.commhub.application.port.out.ClockPort;
import uz.hamkorbank.commhub.application.port.out.ProviderConfigRepository;

/**
 * The registries the provider adapters take their breakers and retries from (PR-01).
 *
 * <p>Plain beans rather than the Resilience4j Spring Boot starter, and programmatic rather than
 * annotation-driven. Two reasons: the policy that matters here — which provider error opens a breaker
 * (§18.1 code 102), which is merely a rejected message — is a property of the error tables of §18.1 and
 * §18.2 and belongs next to them in code where a unit test can reach it; and an AOP proxy around a
 * method called once per message is a cost paid on the OTP path for nothing.
 *
 * <p>Registries rather than free-standing instances so that everything created here is enumerable: the
 * provider health of PR-02 and the {@code circuit breaker state} metric of OBS-01 both read the same
 * objects the adapters call through.
 */
@Configuration
public class ProviderResilienceConfig {

    @Bean
    public CircuitBreakerRegistry providerCircuitBreakerRegistry() {
        return CircuitBreakerRegistry.ofDefaults();
    }

    @Bean
    public RetryRegistry providerRetryRegistry() {
        return RetryRegistry.ofDefaults();
    }

    /**
     * The runtime half of a provider profile: limits and endpoint settings from the database (FR-2.5,
     * AD-07).
     *
     * <p>A bean here rather than a {@code @Component} so that the adapters keep one obvious place where
     * everything shared between them is assembled.
     */
    /** The shared machinery handed to every provider adapter (PR-01, FR-2.5, SEC-04). */
    @Bean
    public ProviderSupport providerSupport(
            ProviderCallExecutor executor,
            ProviderThrottle throttle,
            ProviderRuntimeSettings runtimeSettings,
            ClockPort clock,
            ProviderRestClients clients,
            OutboundContentLog contentLog) {
        return new ProviderSupport(executor, throttle, runtimeSettings, clock, clients, contentLog);
    }

    @Bean
    public ProviderRuntimeSettings providerRuntimeSettings(
            ProviderConfigRepository configuration, ConfigurationCacheProperties cacheProperties) {
        return new ProviderRuntimeSettings(configuration, cacheProperties);
    }
}
