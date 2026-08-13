package com.ai.gateway.adapter.web.filter;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class TraceContextFilterTest {

    @Test
    void invalidClientTraceIdIsReplacedWithServerGeneratedId() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/natural-language/query");
        request.addHeader("X-Trace-Id", "not-a-trace-id");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new TraceContextFilter().doFilter(request, response, new MockFilterChain());

        String traceId = response.getHeader("X-Trace-Id");
        assertThat(traceId).matches("[0-9a-f]{32}");
        assertThat(traceId).isNotEqualTo("not-a-trace-id");
        assertThat(request.getAttribute(TraceContextFilter.ATTR_TRACE_ID)).isEqualTo(traceId);
    }

    @Test
    void validTraceIdIsNormalizedAndPropagated() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/v1/releases/1");
        request.addHeader("X-Trace-Id", "ABCDEF0123456789ABCDEF0123456789");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new TraceContextFilter().doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader("X-Trace-Id"))
                .isEqualTo("abcdef0123456789abcdef0123456789");
        assertThat(request.getAttribute(TraceContextFilter.ATTR_SPAN_NAMES))
                .isEqualTo(TraceContextFilter.PIPELINE_SPANS);
    }

    @Test
    void oversizedRequestIdIsReplacedBeforeMdcAndResponsePropagation() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1");
        request.addHeader("X-Request-Id", "x".repeat(1025));
        MockHttpServletResponse response = new MockHttpServletResponse();

        new TraceContextFilter().doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader("X-Request-Id")).hasSizeLessThanOrEqualTo(128);
    }

    @Test
    void bearerTokenIsNotCopiedIntoTraceContextOrResponse() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1");
        request.addHeader("Authorization", "Bearer secret-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new TraceContextFilter().doFilter(request, response, new MockFilterChain());

        assertThat(request.getAttribute(TraceContextFilter.ATTR_TRACE_ID))
                .asString().doesNotContain("secret-token");
        assertThat(request.getAttribute(TraceContextFilter.ATTR_CLIENT_REQUEST_ID))
                .asString().doesNotContain("secret-token");
        assertThat(response.getHeaderNames()).allMatch(name ->
                !String.valueOf(response.getHeader(name)).contains("secret-token"));
    }
}
