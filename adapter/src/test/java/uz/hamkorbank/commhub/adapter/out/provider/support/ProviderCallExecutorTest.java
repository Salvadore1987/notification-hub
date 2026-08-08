package uz.hamkorbank.commhub.adapter.out.provider.support;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.hamkorbank.commhub.adapter.out.provider.FixedClock;
import uz.hamkorbank.commhub.application.port.out.provider.ProviderAck;
import uz.hamkorbank.commhub.domain.model.type.AttemptResult;
import uz.hamkorbank.commhub.domain.model.type.ErrorClass;

/**
 * The policy every provider adapter inherits (PR-01, §18.1 code 102, FR-6.3).
 *
 * <p>The point under test is the split the executor is built on: a provider that answers — even to say
 * "no" — is a healthy provider, and only a call that produced no answer is allowed to drive retry and
 * the circuit breaker.
 */
class ProviderCallExecutorTest {

    private static final String PROVIDER = "TESTSMS";

    private static final Instant NOW = Instant.parse("2026-08-08T10:00:00Z");

    private ProviderCallExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new ProviderCallExecutor(
                CircuitBreakerRegistry.ofDefaults(), RetryRegistry.ofDefaults(), FixedClock.at(NOW));
    }

    @Test
    @DisplayName("PR-01: a retryable failure is retried inside the attempt up to the configured budget")
    void retriesRetryableFailures() {
        // Arrange
        AtomicInteger calls = new AtomicInteger();
        ProviderResilienceProperties settings = fastRetry(3);

        // Act
        ProviderAck ack = executor.execute(PROVIDER, settings, () -> {
            if (calls.incrementAndGet() < 3) {
                throw ProviderCallException.retryable("503", "service unavailable");
            }
            return ProviderAck.accepted(null, "0", NOW);
        });

        // Assert
        assertThat(calls).hasValue(3);
        assertThat(ack.isAccepted()).isTrue();
    }

    @Test
    @DisplayName("PR-01: a business rejection is returned untouched and never retried")
    void doesNotRetryBusinessRejections() {
        // Arrange
        AtomicInteger calls = new AtomicInteger();

        // Act
        ProviderAck ack = executor.execute(PROVIDER, fastRetry(3), () -> {
            calls.incrementAndGet();
            return ProviderAck.rejected("406", "Invalid content", NOW);
        });

        // Assert
        assertThat(calls).hasValue(1);
        assertThat(ack.result()).isEqualTo(AttemptResult.REJECTED);
        assertThat(ack.errorClass()).isEqualTo(ErrorClass.NON_RETRYABLE);
    }

    @Test
    @DisplayName("§18.1 code 102: a blocking failure opens the breaker at once and is not retried")
    void blockingFailureOpensBreakerImmediately() {
        // Arrange
        AtomicInteger calls = new AtomicInteger();

        // Act
        ProviderAck ack = executor.execute(PROVIDER, fastRetry(3), () -> {
            calls.incrementAndGet();
            throw ProviderCallException.blocking("102", "Account lock");
        });

        // Assert
        assertThat(calls).hasValue(1);
        assertThat(ack.isBlocking()).isTrue();
        assertThat(executor.stateOf(PROVIDER)).contains(CircuitBreaker.State.OPEN);
    }

    @Test
    @DisplayName("FR-6.3: while the breaker is open the provider is not called and the ack stays retryable")
    void openBreakerFailsOverInsteadOfFailing() {
        // Arrange
        executor.execute(PROVIDER, fastRetry(1), () -> {
            throw ProviderCallException.blocking("102", "Account lock");
        });
        AtomicInteger calls = new AtomicInteger();

        // Act
        ProviderAck ack = executor.execute(PROVIDER, fastRetry(1), () -> {
            calls.incrementAndGet();
            return ProviderAck.accepted(null, "0", NOW);
        });

        // Assert
        assertThat(calls).hasValue(0);
        assertThat(ack.responseCode()).isEqualTo(ProviderCallExecutor.CIRCUIT_OPEN_CODE);
        assertThat(ack.isRetryable()).isTrue();
    }

    @Test
    @DisplayName("PR-01: a timeout becomes a timed-out ack rather than an exception")
    void timeoutBecomesAck() {
        // Act
        ProviderAck ack = executor.execute(PROVIDER, fastRetry(1), () -> {
            throw ProviderCallException.timeout("read timed out", null);
        });

        // Assert
        assertThat(ack.result()).isEqualTo(AttemptResult.TIMEOUT);
        assertThat(ack.isRetryable()).isTrue();
    }

    @Test
    @DisplayName("§9.1/§9.2: a failed chunk call yields one ack per submission, all the same verdict")
    void batchFailureIsReportedPerSubmission() {
        // Act
        List<ProviderAck> acks = executor.executeBatch(PROVIDER, fastRetry(1), 3, () -> {
            throw ProviderCallException.retryable("500", "boom");
        });

        // Assert
        assertThat(acks).hasSize(3);
        assertThat(acks).allSatisfy(ack -> assertThat(ack.isRetryable()).isTrue());
    }

    /** Real retry semantics, no real waiting: the backoff is what makes this test slow, not the logic. */
    private static ProviderResilienceProperties fastRetry(int attempts) {
        return new ProviderResilienceProperties(
                attempts, Duration.ofMillis(1), 1.0d, 0.0d, 50.0f, 20, 10, Duration.ofSeconds(30));
    }
}
