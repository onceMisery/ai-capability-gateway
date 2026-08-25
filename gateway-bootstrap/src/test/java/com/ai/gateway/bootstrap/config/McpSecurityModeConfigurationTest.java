package com.ai.gateway.bootstrap.config;

import com.ai.gateway.adapter.mcp.McpSecurityMode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpSecurityModeConfigurationTest {

    @Test
    void allowsNoAuthOnlyInDevelopment() {
        assertThatCode(() -> BeanConfig.assertMcpSecurityModeAllowed(
                McpSecurityMode.NO_AUTH, "development"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsNoAuthOutsideDevelopment() {
        assertThatThrownBy(() -> BeanConfig.assertMcpSecurityModeAllowed(
                McpSecurityMode.NO_AUTH, "production"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("gateway.environment=development");
        assertThatThrownBy(() -> BeanConfig.assertMcpSecurityModeAllowed(
                McpSecurityMode.NO_AUTH, "test"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void keepsAuthenticatedModesAvailableOutsideDevelopment() {
        assertThatCode(() -> BeanConfig.assertMcpSecurityModeAllowed(
                McpSecurityMode.READ_ONLY, "production"))
                .doesNotThrowAnyException();
    }
}
