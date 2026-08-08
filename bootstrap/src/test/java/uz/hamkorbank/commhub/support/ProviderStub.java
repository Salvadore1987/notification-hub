package uz.hamkorbank.commhub.support;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

/**
 * The provider the acceptance and chaos scenarios talk to: one WireMock per JVM, on a free port.
 *
 * <p>A stub rather than the provider's own test contour, for the same reason the persistence tests use
 * a container rather than a shared database — a run has to be able to say what the provider answered.
 * The Bank repeats these scenarios against the real test contours of Playmobile and SMS Gate, and that
 * is what QA-08 asks for; what this proves is that the Hub does its half correctly.
 *
 * <p>Started lazily so that the port is known by the time {@code @DynamicPropertySource} asks for the
 * base URL, and shut down with the JVM.
 */
public final class ProviderStub {

    private static WireMockServer server;

    private ProviderStub() {}

    public static synchronized WireMockServer server() {
        if (server == null) {
            server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
            server.start();
        }
        return server;
    }

    public static String baseUrl() {
        return server().baseUrl();
    }

    /** Drops the stubs and the request journal; a scenario asserts on the calls it caused. */
    public static void reset() {
        server().resetAll();
    }
}
