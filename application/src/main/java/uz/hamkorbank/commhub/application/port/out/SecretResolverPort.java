package uz.hamkorbank.commhub.application.port.out;

import java.util.Optional;

/**
 * Resolves credentials of providers and streams from the secret store (SEC-04, SG-04, PR-03).
 *
 * <p>Neither the domain nor the application layer ever holds a credential: aggregates only carry a
 * reference ({@code Provider.credentialsRef}), which the provider adapters resolve here against
 * Vault/K8s secrets. Values must never be logged; rotation happens without a restart (SEC-04).
 */
public interface SecretResolverPort {

    Optional<String> resolve(String secretRef);

    /** Same as {@link #resolve(String)} but fails fast when the secret is missing. */
    String require(String secretRef);
}
