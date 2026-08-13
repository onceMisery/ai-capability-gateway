package com.ai.gateway.adapter.web;

import com.ai.gateway.adapter.web.controller.NaturalLanguageController;
import com.ai.gateway.domain.port.AuthenticationPort;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class AdminSecurityContractTest {

    @Test
    void webAdapterHasUnifiedAdminAuthenticationFilter() throws Exception {
        Class<?> filter = Class.forName(
                "com.ai.gateway.adapter.web.filter.AdminAuthenticationFilter");
        assertThat(jakarta.servlet.Filter.class.isAssignableFrom(filter)).isTrue();
    }

    @Test
    void clarificationControllerReceivesAuthenticationPort() {
        boolean hasAuthenticationPort = Arrays.stream(NaturalLanguageController.class.getConstructors())
                .flatMap(constructor -> Arrays.stream(constructor.getParameterTypes()))
                .anyMatch(AuthenticationPort.class::equals);

        assertThat(hasAuthenticationPort).isTrue();
    }
}
