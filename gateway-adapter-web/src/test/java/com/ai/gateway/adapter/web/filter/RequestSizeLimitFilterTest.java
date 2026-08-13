package com.ai.gateway.adapter.web.filter;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class RequestSizeLimitFilterTest {

    @Test
    void bodyExactlyAtLimitCanBeReadThroughEndOfStream() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContent("12345678".getBytes(StandardCharsets.UTF_8));
        var wrapper = new RequestSizeLimitFilter.SizeLimitedHttpServletRequestWrapper(request, 8);

        byte[] body = wrapper.getInputStream().readAllBytes();

        assertThat(new String(body, StandardCharsets.UTF_8)).isEqualTo("12345678");
    }

    @Test
    void declaredBodyOverLimitReturnsStandardErrorEnvelope() throws Exception {
        RequestSizeLimitFilter filter = new RequestSizeLimitFilter(8);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContent("123456789".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("\"status\":\"ERROR\"")
                .contains("\"error\"")
                .contains("\"errorCode\":\"RESULT_TOO_LARGE\"");
    }
}
