package uz.hamkorbank.commhub.adapter.out.provider.support;

import uz.hamkorbank.commhub.application.port.out.ClockPort;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * The machinery every provider adapter needs, in one dependency (PR-01, FR-2.5, SEC-04, AD-07).
 *
 * <p>Each adapter is a thin translation of the Hub's contract into one provider's API; everything
 * around that translation — retry and breaker, throughput limits, the runtime half of the provider
 * profile, the clock and the HTTP client factory — is the same for all of them.
 * Passing them individually made the constructors grow with every capability the framework gained, so
 * they travel together and an adapter's own constructor stays about that adapter.
 *
 * @param executor retry + circuit breaker per provider; a call returns a {@code ProviderAck} for any
 *     answer and throws only when there was none (PR-01)
 * @param throttle sustained rate, per-minute ceiling and the per-recipient anti-spam rule (FR-2.5)
 * @param runtimeSettings limits and endpoint settings read from the provider profile (AD-07)
 * @param clients JDK HTTP clients on virtual threads with mandatory timeouts (AR-07)
 */
public record ProviderSupport(
        ProviderCallExecutor executor,
        ProviderThrottle throttle,
        ProviderRuntimeSettings runtimeSettings,
        ClockPort clock,
        ProviderRestClients clients) {

    public ProviderSupport {
        Guard.notNull(executor, "ProviderSupport.executor");
        Guard.notNull(throttle, "ProviderSupport.throttle");
        Guard.notNull(runtimeSettings, "ProviderSupport.runtimeSettings");
        Guard.notNull(clock, "ProviderSupport.clock");
        Guard.notNull(clients, "ProviderSupport.clients");
    }
}
