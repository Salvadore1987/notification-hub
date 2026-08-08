package uz.hamkorbank.commhub.adapter.out.provider.support;

import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Builds the HTTP client of a provider adapter and runs its exchanges (PR-01, AR-07, §9.5).
 *
 * <p>Blocking clients on virtual threads, as AR-07 requires: the JDK client is given a
 * virtual-thread-per-task executor, so a provider that answers in two seconds parks a virtual thread
 * for two seconds and occupies no platform thread. That is what lets a bulk batch keep hundreds of
 * calls in flight without a connection pool sized for them.
 *
 * <p>Both timeouts are always set (PR-01). A read timeout is the only thing standing between a provider
 * that accepts connections and never answers and an unbounded queue of messages waiting on it.
 *
 * <p>Status codes are never turned into exceptions here. Provider APIs put their verdict in the body as
 * often as in the status line (§9.1, §9.2), so every answer that arrived comes back as a
 * {@link ProviderHttpResponse} and only a call that produced no answer throws.
 */
@Component
public class ProviderRestClients {

    /**
     * Creates the client of one provider.
     *
     * <p>One client per adapter, created once: the underlying JDK client owns the connection pool, and
     * building it per call would open a new connection for every message.
     */
    public RestClient create(ProviderHttpProperties http) {
        Guard.notNull(http, "http");
        Guard.isTrue(http.hasBaseUrl(), "a provider adapter needs a base URL");
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(http.connectTimeout())
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(http.readTimeout());
        return RestClient.builder()
                .baseUrl(http.baseUrl())
                .requestFactory(factory)
                .build();
    }

    /**
     * Runs a prepared request and returns whatever came back.
     *
     * @throws ProviderCallException when the call produced no answer — a timeout, a refused connection,
     *     an unreadable stream; the executor turns it into a retryable ack (PR-01)
     */
    public static ProviderHttpResponse send(RestClient.RequestHeadersSpec<?> request) {
        Guard.notNull(request, "request");
        try {
            return request.exchange((ignored, response) ->
                    new ProviderHttpResponse(response.getStatusCode().value(), readBody(response.getBody())));
        } catch (ResourceAccessException e) {
            throw transportFailure(e);
        } catch (RuntimeException e) {
            throw ProviderCallException.transport(
                    "%s: %s".formatted(e.getClass().getSimpleName(), e.getMessage()), e);
        }
    }

    /** Separates "did not answer in time" from "could not be reached", which retry the same but read differently. */
    private static ProviderCallException transportFailure(ResourceAccessException e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) {
                return ProviderCallException.timeout(cause.getMessage(), e);
            }
        }
        return ProviderCallException.transport(e.getMessage(), e);
    }

    private static String readBody(InputStream body) {
        if (body == null) {
            return "";
        }
        try (InputStream stream = body) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw ProviderCallException.transport("the response body could not be read", e);
        }
    }
}
