package uz.hamkorbank.commhub.adapter.out.metrics;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.springframework.stereotype.Component;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Exports the state of every provider circuit breaker (OBS-01, OBS-04, PR-01).
 *
 * <p>"Circuit breaker open" is the alert that tells operations a provider stopped answering before the
 * customers do, so the state has to be a metric and not only a log line. One series per state rather
 * than one numeric series with an encoded state: {@code commhub_provider_circuit_state{state="OPEN"} == 1}
 * is an alert expression anyone can read, while {@code state == 2} is one nobody can.
 *
 * <p>Breakers are created lazily by {@code ProviderCallExecutor} the first time a provider is called, so
 * binding once at startup would export nothing. The registry's event publisher fills the gap: whatever
 * exists now is bound now, and whatever appears later is bound when it appears.
 *
 * <p>Written by hand rather than taken from {@code resilience4j-micrometer}: Phase 7 deliberately took
 * the plain registries instead of the Spring Boot starter, and one binder is a smaller thing to own than
 * a second dependency that brings its own naming convention.
 */
@Component
public class CircuitBreakerMetrics {

    private final MeterRegistry meters;

    public CircuitBreakerMetrics(MeterRegistry meters, CircuitBreakerRegistry breakers) {
        this.meters = Guard.notNull(meters, "meters");
        Guard.notNull(breakers, "breakers");
        breakers.getAllCircuitBreakers().forEach(this::bind);
        breakers.getEventPublisher().onEntryAdded(event -> bind(event.getAddedEntry()));
    }

    private void bind(CircuitBreaker breaker) {
        for (CircuitBreaker.State state : CircuitBreaker.State.values()) {
            Gauge.builder(MetricNames.PROVIDER_CIRCUIT_STATE, breaker, current -> current.getState() == state ? 1 : 0)
                    .tags(Tags.of(MetricNames.TAG_PROVIDER, breaker.getName(), MetricNames.TAG_STATE, state.name()))
                    .description("1 while the breaker of the provider is in this state (PR-01)")
                    .register(meters);
        }
        Gauge.builder(MetricNames.PROVIDER_CIRCUIT_FAILURE_RATE, breaker, current -> current.getMetrics()
                        .getFailureRate())
                .tags(Tags.of(MetricNames.TAG_PROVIDER, breaker.getName()))
                .description("Failure rate of the sliding window in percent; -1 until the window is full")
                .register(meters);
    }
}
