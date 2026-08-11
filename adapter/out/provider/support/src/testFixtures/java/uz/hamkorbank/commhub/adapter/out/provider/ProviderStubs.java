package uz.hamkorbank.commhub.adapter.out.provider;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

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
}
