package uz.hamkorbank.commhub.adapter.in.admin.support;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import uz.hamkorbank.commhub.adapter.in.admin.AdminApi;

/**
 * Reads the justification header of FR-7.3 as UTF-8 text (SEC-03).
 *
 * <p>An HTTP header value is a byte string: a browser refuses to send a header whose value has a code
 * point above 255, so a justification typed in Russian — which is every justification this panel will
 * ever get — cannot travel as it was typed, and the servlet container would read it as ISO-8859-1 even
 * if it did. The panel therefore percent-encodes the value (RFC 3986 over UTF-8) and it is decoded here,
 * once, so that every controller keeps reading one {@code String} and the journal keeps the operator's
 * own words rather than an escape sequence.
 *
 * <p>Only the admin BFF is touched. The {@code /api/v1} header of §8.2 is a published contract whose
 * callers are the Bank's systems, and changing how their value is read is not this panel's business.
 *
 * <p>A value that is not percent-encoded passes through unchanged: {@code +} is left alone (a plus in a
 * sentence is a plus, not a space — that reading belongs to form encoding, not to a header), and a stray
 * {@code %} makes decoding fail rather than the request.
 */
@Component
public class ReasonHeaderFilter extends OncePerRequestFilter {

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(AdminApi.BASE) || request.getHeader(AdminApi.REASON_HEADER) == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        chain.doFilter(new DecodedReason(request, decode(request.getHeader(AdminApi.REASON_HEADER))), response);
    }

    static String decode(String value) {
        if (value == null || value.indexOf('%') < 0) {
            return value;
        }
        try {
            return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return value;
        }
    }

    private static final class DecodedReason extends HttpServletRequestWrapper {

        private final String reason;

        private DecodedReason(HttpServletRequest request, String reason) {
            super(request);
            this.reason = reason;
        }

        @Override
        public String getHeader(String name) {
            return AdminApi.REASON_HEADER.equalsIgnoreCase(name) ? reason : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            return AdminApi.REASON_HEADER.equalsIgnoreCase(name)
                    ? Collections.enumeration(Collections.singletonList(reason))
                    : super.getHeaders(name);
        }
    }
}
