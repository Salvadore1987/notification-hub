/**
 * What every provider adapter shares: transport, resilience, throttling and masking (PR-01, PR-03, §9.5).
 *
 * <p>The point of this package is that "adding a provider is a new adapter only" (AR-04) stays true
 * without each new adapter re-deciding how long to wait, when to retry and what may appear in a log
 * line. An adapter here supplies two things — the shape of its requests and its own error table — and
 * inherits the rest.
 *
 * <p>The contract with {@link uz.hamkorbank.commhub.adapter.out.provider.support.ProviderCallExecutor}
 * is worth stating once: a provider call returns a {@code ProviderAck} for any answer the provider
 * gave, and throws
 * {@link uz.hamkorbank.commhub.adapter.out.provider.support.ProviderCallException} only when there was
 * no answer. Retries and the circuit breaker key off the exception alone, so a provider that is
 * healthily rejecting bad content never trips a breaker, and one that has stopped answering trips it
 * quickly.
 *
 * <p>Configuration of a provider lives in the properties of its own package for now
 * ({@code commhub.provider.<code>}); Phase 8 moves the transport settings into
 * {@code provider.endpoint_config} so an operator can change them from the admin panel without a
 * restart (AD-07, NF-07). Credentials never move there — they arrive as values from the environment of
 * the pod, and {@link uz.hamkorbank.commhub.adapter.out.provider.support.Blobs} is what lets a
 * multi-line one (the FCM service account, the APNs {@code .p8}) travel in a variable (SEC-04, SG-04,
 * ADR-0044).
 */
package uz.hamkorbank.commhub.adapter.out.provider.support;
