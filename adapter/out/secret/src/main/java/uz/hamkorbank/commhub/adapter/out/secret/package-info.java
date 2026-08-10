/**
 * Driven adapter: credentials of providers and streams (SEC-04, SG-04, PR-03).
 *
 * <p>Implements {@code SecretResolverPort}. Neither the domain nor the application layer ever sees a
 * credential — aggregates carry a reference ({@code Provider.credentialsRef}), provider adapters carry
 * a {@code …-ref} property, and this package is the single place that turns one into a value.
 *
 * <p>The source is the process environment: {@code env:NAME}, optionally with a {@code base64:}
 * modifier for a multi-line blob such as the FCM service account or the APNs {@code .p8} key. A
 * {@code prop:} scheme and the {@code commhub.secrets.values} literals exist for the local stack and
 * the tests. There is no secret directory and no {@code file:} scheme — see ADR-0036, which replaced
 * ADR-0021.
 *
 * <p>The Hub deliberately does not speak to Vault itself — that would make it hold a Vault token (one
 * more credential to protect and rotate) and would put Vault on the sending path of every message.
 * How the value reaches the pod's environment is the platform's business.
 *
 * <p>Values live in a short-TTL cache, which keeps a per-message provider call away from the lookup.
 * It is not what applies a rotation: an environment variable cannot change inside a running process,
 * so rotating a credential is a rolling restart of the deployment.
 */
package uz.hamkorbank.commhub.adapter.out.secret;
