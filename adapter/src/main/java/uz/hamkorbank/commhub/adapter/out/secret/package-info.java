/**
 * Driven adapter: credentials of providers and streams (SEC-04, SG-04, PR-03).
 *
 * <p>Implements {@code SecretResolverPort}. Neither the domain nor the application layer ever sees a
 * credential — aggregates carry a reference ({@code Provider.credentialsRef}), provider adapters carry
 * a {@code …-ref} property, and this package is the single place that turns one into a value.
 *
 * <p>The source is the platform's own mechanism: environment variables, a mounted Kubernetes secret,
 * or a rendered Vault template. The Hub deliberately does not speak to Vault itself — that would make
 * the Hub hold a Vault token (one more credential to protect and rotate) and would put Vault on the
 * sending path of every message.
 *
 * <p>Values live in a short-TTL cache, which is what makes rotation apply without a restart (SEC-04)
 * while keeping a per-message provider call away from the filesystem.
 */
package uz.hamkorbank.commhub.adapter.out.secret;
