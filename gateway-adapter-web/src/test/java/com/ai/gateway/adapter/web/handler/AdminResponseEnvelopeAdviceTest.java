package com.ai.gateway.adapter.web.handler;

import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AdminResponseEnvelopeAdviceTest {

    @Test
    void rejectedLegacyBodyKeepsStructuredErrorAndDoesNotExposeErrorAsData() {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("POST", "/admin/v1/manifests:import");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "REJECTED");
        body.put("error", "invalid manifest");

        Object result = new AdminResponseEnvelopeAdvice().beforeBodyWrite(
                body, null, MediaType.APPLICATION_JSON,
                MappingJackson2HttpMessageConverter.class,
                new ServletServerHttpRequest(servletRequest), null);

        assertThat(result).isInstanceOf(Map.class);
        Map<?, ?> response = (Map<?, ?>) result;
        assertThat(response.get("error")).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) response.get("error")).get("message"))
                .isEqualTo("invalid manifest");
        assertThat(((Map<?, ?>) response.get("data")).get("error")).isNull();
    }
}
