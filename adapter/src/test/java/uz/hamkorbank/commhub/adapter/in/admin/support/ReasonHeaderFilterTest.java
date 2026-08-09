package uz.hamkorbank.commhub.adapter.in.admin.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Collections;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import uz.hamkorbank.commhub.adapter.in.admin.AdminApi;

/** FR-7.3: the justification an operator typed in Russian has to reach the journal as words. */
class ReasonHeaderFilterTest {

    private final ReasonHeaderFilter filter = new ReasonHeaderFilter();

    @Test
    @DisplayName("a percent-encoded justification is read back as UTF-8 text")
    void decodesTheJustification() throws Exception {
        // Arrange
        HttpServletRequest request = adminRequestWithReason(
                "%D0%B4%D1%83%D0%B1%D0%BB%D1%8C%20%D0%B8%D0%BD%D1%86%D0%B8%D0%B4%D0%B5%D0%BD%D1%82%D0%B0");
        FilterChain chain = mock(FilterChain.class);

        // Act
        filter.doFilter(request, mock(HttpServletResponse.class), chain);

        // Assert
        assertThat(passedThrough(chain).getHeader(AdminApi.REASON_HEADER)).isEqualTo("дубль инцидента");
    }

    @Test
    @DisplayName("a plus sign stays a plus sign — this is a header, not a form field")
    void keepsAPlusSign() {
        // Arrange + Act + Assert
        assertThat(ReasonHeaderFilter.decode("a+b%20c")).isEqualTo("a+b c");
    }

    @Test
    @DisplayName("a value that is not percent-encoded passes through unchanged")
    void leavesPlainTextAlone() {
        // Arrange + Act + Assert
        assertThat(ReasonHeaderFilter.decode("manual cleanup")).isEqualTo("manual cleanup");
        assertThat(ReasonHeaderFilter.decode(null)).isNull();
    }

    @Test
    @DisplayName("a malformed escape costs the decoding, never the request")
    void survivesAMalformedEscape() {
        // Arrange + Act + Assert
        assertThat(ReasonHeaderFilter.decode("100% done")).isEqualTo("100% done");
    }

    @Test
    @DisplayName("only the admin BFF is touched — the published /api/v1 header is read as it was")
    void leavesTheSourceSystemApiAlone() {
        // Arrange
        HttpServletRequest sourceSystemCall = mock(HttpServletRequest.class);
        when(sourceSystemCall.getRequestURI()).thenReturn("/api/v1/batches/1/actions/pause");
        when(sourceSystemCall.getHeader(AdminApi.REASON_HEADER)).thenReturn("%D0%B4%D1%83%D0%B1%D0%BB%D1%8C");

        HttpServletRequest withoutReason = mock(HttpServletRequest.class);
        when(withoutReason.getRequestURI()).thenReturn(AdminApi.DLQ + "/retry");
        when(withoutReason.getHeader(AdminApi.REASON_HEADER)).thenReturn(null);

        // Act + Assert
        assertThat(filter.shouldNotFilter(sourceSystemCall)).isTrue();
        assertThat(filter.shouldNotFilter(withoutReason)).isTrue();
    }

    private HttpServletRequest adminRequestWithReason(String value) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn(AdminApi.DLQ + "/archive");
        when(request.getHeader(AdminApi.REASON_HEADER)).thenReturn(value);
        when(request.getHeaderNames())
                .thenReturn(Collections.enumeration(Collections.singletonList(AdminApi.REASON_HEADER)));
        when(request.getDispatcherType()).thenReturn(DispatcherType.REQUEST);
        when(request.getAttribute(any())).thenReturn(null);
        return request;
    }

    private HttpServletRequest passedThrough(FilterChain chain) throws Exception {
        ArgumentCaptor<HttpServletRequest> captor = ArgumentCaptor.forClass(HttpServletRequest.class);
        verify(chain).doFilter(captor.capture(), any());
        return captor.getValue();
    }
}
