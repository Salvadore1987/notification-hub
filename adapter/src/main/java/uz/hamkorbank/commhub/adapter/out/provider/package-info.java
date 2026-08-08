/**
 * Driven adapters: the delivery providers (AR-04, MP-05, §9).
 *
 * <p>One sub-package per integration, plus {@code support} for what they all share. Each implements the
 * output port of its channel — {@code SmsProviderPort} in the MVP, {@code EmailProviderPort} and
 * {@code PushProviderPort} in the later stages of §16 — and each is selected by an {@code adapterType}
 * the router carries on the {@code ProviderRef}. Adding a provider adds a sub-package here and changes
 * nothing in {@code domain/} or {@code application/}; that is AR-04, and it is checked by ArchUnit.
 *
 * <p>Three things are the same for every one of them and live in {@code support}: the HTTP client with
 * its timeouts, the retry and circuit breaker of PR-01, and the masking rules of PR-03. Two things are
 * always different and live in the provider's own package: the shape of its requests, and the table
 * that says what its error codes mean (§18.1 for Playmobile, §18.2 for SMS Gate). Nothing else should
 * ever need to be provider-specific — when it does, the split above is the thing to re-examine.
 *
 * <p>A provider's inbound half — the translator for its delivery reports — lives with it here rather
 * than in {@code adapter/in/callback}, which owns only the endpoint and its authentication. The status
 * vocabulary of a provider belongs next to its error vocabulary.
 *
 * <p>The optional SMPP transport of PM-04 is not built. §9.1 settles the MVP on HTTP and PM-04 marks
 * SMPP as an option to be enabled by configuration later; a session-based protocol with binds,
 * enquire_link keepalives, window management and its own concatenation rules is a second transport, not
 * a variation of this one, and shipping an untested one behind a flag would be worse than not having it.
 */
package uz.hamkorbank.commhub.adapter.out.provider;
