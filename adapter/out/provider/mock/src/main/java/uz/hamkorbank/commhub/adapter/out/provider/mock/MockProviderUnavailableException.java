package uz.hamkorbank.commhub.adapter.out.provider.mock;

/**
 * The fake provider gave no answer at all (ADR-0041, PR-01).
 *
 * <p>Thrown rather than returned, because that is the load-bearing distinction of the provider
 * framework: an answer — any answer, including a refusal — is a {@code ProviderAck}, and only the
 * absence of one is an exception. Retry, failover and the circuit breaker key off the exception alone.
 */
public class MockProviderUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public MockProviderUnavailableException(String message) {
        super(message);
    }
}
