package uz.hamkorbank.commhub.adapter.out.provider.support;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import uz.hamkorbank.commhub.application.port.out.ClockPort;
import uz.hamkorbank.commhub.application.port.out.provider.ProviderAck;
import uz.hamkorbank.commhub.domain.model.type.ErrorClass;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Runs one provider call under the timeout, retry and circuit breaker of that provider (PR-01, §9.5).
 *
 * <p>Every SMS, email and push adapter goes through here, which is what makes "resilience per provider"
 * a property of the deployment rather than of whoever wrote the newest adapter. The adapter supplies a
 * call that either returns a {@link ProviderAck} — the provider answered, whatever it said — or throws
 * a {@link ProviderCallException} — the call itself failed.
 *
 * <p>That split is the whole design. Only the exception is retried and only the exception counts
 * towards the failure rate, so a stream of messages the provider rejects for their content never opens
 * a breaker: the integration is healthy, the messages are not. A blocking failure (§18.1 code 102
 * Account lock) opens the breaker immediately without waiting for a rate — an account lock does not get
 * better with the next nineteen calls, and every one of them costs a message its SLA.
 *
 * <p>An open breaker is reported as a <em>retryable</em> ack, not as a permanent failure: the message
 * has to reach the next provider of the fallback chain, not the DLQ (FR-2.2, FR-6.3).
 *
 * <p>Runs on whatever thread called it, which under {@code spring.threads.virtual.enabled} is a virtual
 * one — the backoff sleeps park a virtual thread and cost no platform thread (AR-07).
 */
@Component
public class ProviderCallExecutor {

    public static final String CIRCUIT_OPEN_CODE = "CIRCUIT_OPEN";

    private static final Logger LOG = LoggerFactory.getLogger(ProviderCallExecutor.class);

    private final CircuitBreakerRegistry circuitBreakers;
    private final RetryRegistry retries;
    private final ClockPort clock;

    public ProviderCallExecutor(CircuitBreakerRegistry circuitBreakers, RetryRegistry retries, ClockPort clock) {
        this.circuitBreakers = Guard.notNull(circuitBreakers, "circuitBreakers");
        this.retries = Guard.notNull(retries, "retries");
        this.clock = Guard.notNull(clock, "clock");
    }

    /**
     * Executes {@code call} under the policy of {@code providerCode}.
     *
     * @param call the provider exchange; returns an ack for any answer, throws
     *     {@link ProviderCallException} when there was none
     * @return what the sending saga sees — never an exception (PR-01)
     */
    public ProviderAck execute(String providerCode, ProviderResilienceProperties settings, Supplier<ProviderAck> call) {
        return run(providerCode, settings, call, ack -> ack);
    }

    /**
     * Same policy for a call that submits a chunk (Playmobile bulk {@code /send}, SMS Gate
     * {@code /api/v2/send_msgs}).
     *
     * <p>A failure of the call itself is a failure of every message in the chunk, so the single ack is
     * repeated {@code size} times — the port's answers stay positional whatever happened (§9.1, §9.2).
     *
     * @param size number of submissions in the chunk
     */
    public List<ProviderAck> executeBatch(
            String providerCode, ProviderResilienceProperties settings, int size, Supplier<List<ProviderAck>> call) {
        Guard.positive(size, "size");
        return run(providerCode, settings, call, ack -> Collections.nCopies(size, ack));
    }

    private <T> T run(
            String providerCode,
            ProviderResilienceProperties settings,
            Supplier<T> call,
            Function<ProviderAck, T> onFailure) {
        Guard.notBlank(providerCode, "providerCode");
        Guard.notNull(settings, "settings");
        Guard.notNull(call, "call");
        CircuitBreaker breaker = circuitBreaker(providerCode, settings);
        Supplier<T> guarded = CircuitBreaker.decorateSupplier(breaker, call);
        try {
            return retry(providerCode, settings).executeSupplier(guarded);
        } catch (CallNotPermittedException e) {
            LOG.warn("Provider {} is not called: its circuit breaker is {}", providerCode, breaker.getState());
            return onFailure.apply(ProviderAck.failed(
                    CIRCUIT_OPEN_CODE,
                    ErrorClass.RETRYABLE,
                    "circuit breaker of " + providerCode + " is open",
                    clock.now()));
        } catch (ProviderCallException e) {
            return onFailure.apply(failureAck(providerCode, breaker, e));
        }
    }

    /** State of a provider's breaker; read by the health view of PR-02 and the metrics of OBS-01. */
    public Optional<CircuitBreaker.State> stateOf(String providerCode) {
        return circuitBreakers
                .find(Guard.notBlank(providerCode, "providerCode"))
                .map(CircuitBreaker::getState);
    }

    private ProviderAck failureAck(String providerCode, CircuitBreaker breaker, ProviderCallException e) {
        if (e.isBlocking()) {
            openNow(providerCode, breaker, e);
        }
        LOG.warn(
                "Provider {} call failed: code={} class={} detail={}",
                providerCode,
                e.responseCode(),
                e.errorClass(),
                e.getMessage());
        if (e.isTimeout()) {
            return ProviderAck.timedOut(clock.now());
        }
        return ProviderAck.failed(e.responseCode(), e.errorClass(), e.getMessage(), clock.now());
    }

    /**
     * Opens the breaker without waiting for the failure rate (§18.1 code 102).
     *
     * <p>Logged at ERROR because it stops a provider for everyone: this is the line the alert of OBS-04
     * fires on.
     */
    private static void openNow(String providerCode, CircuitBreaker breaker, ProviderCallException e) {
        LOG.error(
                "Provider {} reported a blocking failure ({}): opening its circuit breaker — {}",
                providerCode,
                e.responseCode(),
                e.getMessage());
        if (breaker.getState() == CircuitBreaker.State.CLOSED || breaker.getState() == CircuitBreaker.State.HALF_OPEN) {
            breaker.transitionToOpenState();
        }
    }

    private CircuitBreaker circuitBreaker(String providerCode, ProviderResilienceProperties settings) {
        return circuitBreakers.circuitBreaker(providerCode, () -> CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(settings.slidingWindowSize())
                .minimumNumberOfCalls(settings.minimumCalls())
                .failureRateThreshold(settings.failureRateThreshold())
                .waitDurationInOpenState(settings.openDuration())
                // Failback (FR-6.3): a single probe decides whether the provider is back.
                .permittedNumberOfCallsInHalfOpenState(1)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .recordExceptions(ProviderCallException.class)
                .build());
    }

    private Retry retry(String providerCode, ProviderResilienceProperties settings) {
        return retries.retry(providerCode, () -> RetryConfig.custom()
                .maxAttempts(settings.maxAttempts())
                .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(
                        settings.initialBackoff(), settings.backoffMultiplier(), settings.jitter()))
                // A blocking failure is a property of the account, not of the call: retrying it
                // inside the attempt only delays the failover the message actually needs.
                .retryOnException(
                        throwable -> throwable instanceof ProviderCallException failure && !failure.isBlocking())
                .build());
    }
}
