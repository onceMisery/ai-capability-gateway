package com.ai.gateway.adapter.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ai.gateway.domain.model.Principal;
import com.ai.gateway.domain.port.AuthenticationPort;
import com.ai.gateway.domain.port.TelemetryPort;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link McpWebMvcTransportAdapter} 的单元测试，验证仅以固定 Meta-Tool 与 WebMVC 路由构建
 * MCP 服务，且工具列表变更通知被禁用。
 *
 * @author cmiracle@163.com
 */
class McpWebMvcTransportAdapterTest {

    @Test
    void buildsSdkServerWithOnlyFixedMetaToolsAndWebMvcRoutes() {
        McpGatewayAdapter gateway = mock(McpGatewayAdapter.class);
        when(gateway.toolsList()).thenReturn(McpMetaToolCatalog.tools());
        AuthenticationPort authentication = mock(AuthenticationPort.class);
        when(authentication.authenticate(org.mockito.ArgumentMatchers.any())).thenReturn(
                new Principal("test", 1L, List.of(), List.of(), Instant.now(), "test"));

        McpWebMvcTransportAdapter adapter = new McpWebMvcTransportAdapter(
                new ObjectMapper(), gateway, authentication, mock(TelemetryPort.class),
                4, Duration.ofMinutes(5));

        assertThat(adapter.routerFunction()).isNotNull();
        assertThat(adapter.transportProvider()).isNotNull();
        assertThat(adapter.server().getServerCapabilities().tools()).isNotNull();
        assertThat(adapter.server().getServerCapabilities().tools().listChanged())
                .isFalse();
    }
}
