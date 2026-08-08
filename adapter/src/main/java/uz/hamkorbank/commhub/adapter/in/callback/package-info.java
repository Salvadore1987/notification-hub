/**
 * Driving adapter: delivery reports pushed by the providers (PM-02, SG-02, SEC-07).
 *
 * <p>This package owns everything that is the same for every provider — the endpoint, who is allowed
 * to call it, and how the report reaches the use case — and nothing that is specific to one. The
 * vocabulary of a provider is its own business: each supplies a
 * {@link uz.hamkorbank.commhub.adapter.in.callback.ProviderCallbackTranslator} that turns its payload
 * into canonical status commands using the tables of §18.1 and §18.2. Those translators live with their
 * adapters under {@code adapter/out/provider}; a provider nobody has taught this endpoint about is
 * answered 404.
 *
 * <p>Two things protect it (SEC-07): the caller's address has to be on the allowlist agreed with the
 * provider, and the request has to carry the shared secret. Both are per provider, because the two SMS
 * providers of the MVP authenticate differently and a single global secret would mean the weaker one
 * sets the bar for the other.
 *
 * <p>Idempotency is not implemented here but relied upon: providers repeat their callbacks, and
 * {@code ProcessProviderStatus} is written so that a report changing nothing is answered successfully
 * (AD-06). The adapter therefore answers 200 for both, which is what stops a provider from retrying a
 * report the Hub has already applied.
 */
package uz.hamkorbank.commhub.adapter.in.callback;
