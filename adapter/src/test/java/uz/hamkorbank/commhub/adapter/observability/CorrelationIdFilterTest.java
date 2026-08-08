package uz.hamkorbank.commhub.adapter.observability;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** OBS-02/OBS-03/FR-8.6: one identifier ties the caller's log line to the Hub's. */
class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter(noTracer());

    @AfterEach
    void clearContext() {
        MDC.clear();
    }

    @Test
    @DisplayName("the caller's correlation id reaches the MDC and comes back in the response")
    void propagatesTheCallersCorrelationId() throws Exception {
        // Arrange
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/messages");
        request.addHeader(CorrelationIdFilter.HEADER, "crm-42");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seenInsideTheChain = new AtomicReference<>();

        // Act
        filter.doFilter(request, response, capture(seenInsideTheChain));

        // Assert
        assertThat(seenInsideTheChain.get()).isEqualTo("crm-42");
        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo("crm-42");
    }

    @Test
    @DisplayName("a request without one gets an identifier anyway — a log line without it is unusable")
    void generatesOneWhenTheCallerSendsNone() throws Exception {
        // Arrange
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seenInsideTheChain = new AtomicReference<>();

        // Act
        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/messages"), response, capture(seenInsideTheChain));

        // Assert
        assertThat(seenInsideTheChain.get()).isNotBlank();
        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo(seenInsideTheChain.get());
    }

    @Test
    @DisplayName("an absurdly long header is replaced rather than trusted, and the context is cleared afterwards")
    void refusesAnOversizedHeaderAndCleansUp() throws Exception {
        // Arrange
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/messages");
        request.addHeader(CorrelationIdFilter.HEADER, "x".repeat(200));
        AtomicReference<String> seenInsideTheChain = new AtomicReference<>();

        // Act
        filter.doFilter(request, new MockHttpServletResponse(), capture(seenInsideTheChain));

        // Assert
        assertThat(seenInsideTheChain.get()).isNotEqualTo("x".repeat(200));
        assertThat(MDC.get(LogContext.CORRELATION_ID)).isNull();
    }

    private static FilterChain capture(AtomicReference<String> target) {
        return (request, response) -> target.set(MDC.get(LogContext.CORRELATION_ID));
    }

    /** No tracer deployed: tracing is a deployment decision and its absence must cost only a trace. */
    private static ObjectProvider<io.micrometer.tracing.Tracer> noTracer() {
        return new ObjectProvider<>() {
            @Override
            public io.micrometer.tracing.Tracer getObject(Object... args) {
                throw new UnsupportedOperationException();
            }

            @Override
            public io.micrometer.tracing.Tracer getIfAvailable() {
                return null;
            }

            @Override
            public io.micrometer.tracing.Tracer getIfUnique() {
                return null;
            }

            @Override
            public io.micrometer.tracing.Tracer getObject() {
                throw new UnsupportedOperationException();
            }
        };
    }
}
