package uz.hamkorbank.commhub.adapter.observability;

import io.micrometer.tracing.BaggageInScope;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import uz.hamkorbank.commhub.domain.model.vo.CorrelationId;

/**
 * Gives every HTTP request a correlation id and puts it where both the logs and the trace can see it
 * (OBS-02, OBS-03, FR-8.6).
 *
 * <p>The id is the source system's if it sent one, and a new one otherwise — never absent, because the
 * point of FR-8.6 is that one identifier links the Bank's system, the Hub's log lines and the provider
 * call. It is echoed back in the response so the caller can log the same value without having to
 * remember what it sent.
 *
 * <p>It goes into two places. The MDC is what the log platform indexes; the tracing baggage is what
 * survives the process boundary, so a span exported to the Bank's collector carries the source system's
 * id and not only the Hub's trace id. Baggage is written only if a tracer is deployed at all — tracing
 * is a deployment decision (NF-06) and its absence must cost a trace, never a request.
 *
 * <p>Runs before everything, including security: a request rejected by authentication is exactly the one
 * whose log line needs an identifier.
 *
 * <p>The header wins over the {@code correlationId} field of the IK-03 body, and the two are not merged.
 * A body is parsed per message and a batch carries many; a header describes the call, which is what a
 * log line and a span are about.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";

    private final ObjectProvider<Tracer> tracers;

    public CorrelationIdFilter(ObjectProvider<Tracer> tracers) {
        this.tracers = tracers;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String correlationId = correlationIdOf(request);
        response.setHeader(HEADER, correlationId);
        try (LogContext ignored = LogContext.of(LogContext.CORRELATION_ID, correlationId);
                BaggageInScope baggage = openBaggage(correlationId)) {
            chain.doFilter(request, response);
        }
    }

    private static String correlationIdOf(HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        if (header == null || header.isBlank() || header.length() > CorrelationId.MAX_LENGTH) {
            return CorrelationId.newId().value();
        }
        return header.trim();
    }

    /**
     * Baggage scope, or Micrometer's no-op scope when no tracer is deployed.
     *
     * <p>A closeable that does nothing keeps the try-with-resources above readable; the alternative is a
     * nullable resource and a branch around the whole request.
     */
    private BaggageInScope openBaggage(String correlationId) {
        Tracer tracer = tracers.getIfAvailable();
        return tracer == null
                ? BaggageInScope.NOOP
                : tracer.createBaggageInScope(LogContext.CORRELATION_ID, correlationId);
    }
}
