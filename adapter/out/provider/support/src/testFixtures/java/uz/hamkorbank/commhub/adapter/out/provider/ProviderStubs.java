package uz.hamkorbank.commhub.adapter.out.provider;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.util.Optional;
import uz.hamkorbank.commhub.application.port.out.SecretResolverPort;

/**
 * A stubbed provider for the contract tests of QA-04 and PR-04.
 *
 * <p>The stubs are written from the request and response shapes printed in §9.1, §9.2, §18.1 and §18.2,
 * which is what makes them worth running: they fail when the adapter stops speaking the documented
 * contract, not when a mock's expectations drift.
 */
public final class ProviderStubs {

    private ProviderStubs() {}

    /** A server on an ephemeral port, so parallel test classes never collide. */
    public static WireMockServer startServer() {
        WireMockServer server =
                new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        return server;
    }

    /**
     * A secret store holding exactly the two references a provider adapter asks for.
     *
     * <p>Not a mock: the contract of {@code SecretResolverPort} — resolve returns empty, require throws
     * — is part of what the adapters rely on, and a stub that silently returns null would hide it.
     */
    public static SecretResolverPort secrets(String firstRef, String firstValue, String secondRef, String secondValue) {
        return new SecretResolverPort() {

            @Override
            public Optional<String> resolve(String secretRef) {
                if (firstRef.equals(secretRef)) {
                    return Optional.of(firstValue);
                }
                return secondRef.equals(secretRef) ? Optional.of(secondValue) : Optional.empty();
            }

            @Override
            public String require(String secretRef) {
                return resolve(secretRef).orElseThrow(() -> new IllegalStateException("no secret for " + secretRef));
            }
        };
    }
}
