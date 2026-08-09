package uz.hamkorbank.commhub.adapter.out.provider.support;

import java.time.Duration;

/**
 * Transport settings of one provider integration (PR-01, §9.5).
 *
 * <p>Both timeouts are mandatory in effect — a provider call without a read timeout parks a virtual
 * thread until the socket gives up, and with a bulk batch in flight that is how a queue of unbounded
 * length appears behind a provider that has stopped answering. The defaults are deliberately short:
 * the sending saga retries and fails over (PR-01, FR-6.3), so waiting longer buys nothing.
 *
 * @param baseUrl root of the provider API, without a trailing slash
 * @param connectTimeout budget for establishing the connection
 * @param readTimeout budget for the answer once the request is on the wire
 */
public record ProviderHttpProperties(String baseUrl, Duration connectTimeout, Duration readTimeout) {

    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(3);

    public static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(10);

    public ProviderHttpProperties {
        baseUrl = baseUrl == null ? null : stripTrailingSlash(baseUrl.trim());
        connectTimeout = positiveOr(connectTimeout, DEFAULT_CONNECT_TIMEOUT);
        readTimeout = positiveOr(readTimeout, DEFAULT_READ_TIMEOUT);
    }

    public static ProviderHttpProperties of(String baseUrl) {
        return new ProviderHttpProperties(baseUrl, null, null);
    }

    public boolean hasBaseUrl() {
        return baseUrl != null && !baseUrl.isBlank();
    }

    private static Duration positiveOr(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
