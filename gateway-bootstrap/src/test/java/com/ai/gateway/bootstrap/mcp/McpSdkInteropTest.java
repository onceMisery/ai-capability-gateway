package com.ai.gateway.bootstrap.mcp;

import com.ai.gateway.adapter.mcp.McpGatewayAdapter;
import com.ai.gateway.adapter.mcp.McpRequestContextFilter;
import com.ai.gateway.adapter.mcp.McpWebMvcTransportAdapter;
import com.ai.gateway.application.agent.AgentCapabilityResolver;
import com.ai.gateway.application.agent.AgentHostConnector;
import com.ai.gateway.domain.model.RequestContext;
import com.ai.gateway.domain.model.Principal;
import com.ai.gateway.domain.port.AuthenticationPort;
import com.ai.gateway.domain.port.TelemetryPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

import java.net.http.HttpRequest;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * McpSdkInteropTest 类。
 *
 * @author cmiracle@163.com
 */
@SpringBootTest(
        classes = McpSdkInteropTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext
class McpSdkInteropTest {

    @LocalServerPort
    private int port;

    @org.springframework.beans.factory.annotation.Autowired
    private AgentHostConnector connector;

    @Test
    void realSdkClientInitializesListsFixedToolsAndCallsResolve() {
        HttpClientSseClientTransport transport = HttpClientSseClientTransport
                .builder("http://127.0.0.1:" + port)
                .sseEndpoint("/mcp/sse")
                .requestBuilder(HttpRequest.newBuilder()
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer interop-token"))
                .build();

        try (McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(10))
                .initializationTimeout(Duration.ofSeconds(10))
                .build()) {
            McpSchema.InitializeResult initialized = client.initialize();
            McpSchema.ListToolsResult tools = client.listTools();
            McpSchema.CallToolResult result = client.callTool(
                    new McpSchema.CallToolRequest("gateway_resolve",
                            Map.of("query", "query order", "agentTurnId", "turn-1")));

            assertThat(initialized.serverInfo().name()).isEqualTo("ai-capability-gateway");
            assertThat(tools.tools()).extracting(McpSchema.Tool::name)
                    .containsExactly("gateway_resolve", "gateway_call");
            assertThat(result.isError()).isFalse();
            assertThat(result.content()).singleElement().isInstanceOfSatisfying(
                    McpSchema.TextContent.class,
                    content -> assertThat(content.text()).contains("RESOLVED"));
            verify(connector).resolve(
                    argThat(context -> "Bearer interop-token".equals(
                            context.header("Authorization"))),
                    anyString(), anyString(), anyString(), anyInt());
        }
    }

    @Test
    void unauthenticatedHandshakeIsRejectedAndSessionIsPrincipalBound() throws Exception {
        HttpClient httpClient = HttpClient.newHttpClient();
        HttpResponse<Void> unauthenticated = httpClient.send(
                HttpRequest.newBuilder(URI.create(
                                "http://127.0.0.1:" + port + "/mcp/sse"))
                        .GET().build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(unauthenticated.statusCode()).isEqualTo(401);

        HttpRequest handshake = HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + port + "/mcp/sse"))
                .header("Authorization", "Bearer identity-a")
                .GET().build();
        HttpResponse<Stream<String>> sse = httpClient.send(
                handshake, HttpResponse.BodyHandlers.ofLines());
        assertThat(sse.statusCode()).isEqualTo(200);
        try (Stream<String> lines = sse.body()) {
            String endpoint = lines
                    .filter(line -> line.startsWith("data:"))
                    .map(line -> line.substring("data:".length()).trim())
                    .findFirst()
                    .orElseThrow();
            HttpResponse<Void> mismatched = httpClient.send(
                    HttpRequest.newBuilder(URI.create(
                                    "http://127.0.0.1:" + port + endpoint))
                            .header("Content-Type", "application/json")
                            .header("Authorization", "Bearer identity-b")
                            .POST(HttpRequest.BodyPublishers.ofString("{}"))
                            .build(),
                    HttpResponse.BodyHandlers.discarding());

            assertThat(mismatched.statusCode()).isEqualTo(403);
        }
    }

    @SpringBootConfiguration(proxyBeanMethods = false)
    @ImportAutoConfiguration({
            ServletWebServerFactoryAutoConfiguration.class,
            DispatcherServletAutoConfiguration.class,
            WebMvcAutoConfiguration.class,
            ErrorMvcAutoConfiguration.class,
            JacksonAutoConfiguration.class
    })
    static class TestApplication {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        AgentHostConnector agentHostConnector() {
            AgentHostConnector connector = mock(AgentHostConnector.class);
            when(connector.resolve(any(RequestContext.class), anyString(), anyString(),
                    anyString(), anyInt())).thenReturn(new AgentHostConnector.ResolveResult(
                    new AgentCapabilityResolver.Resolution(
                            AgentCapabilityResolver.Status.RESOLVED, null,
                            8L, 42L, List.of(), null, null), null));
            return connector;
        }

        @Bean
        AuthenticationPort authenticationPort() {
            AuthenticationPort authentication = mock(AuthenticationPort.class);
            when(authentication.authenticate(any(RequestContext.class))).thenAnswer(invocation -> {
                RequestContext context = invocation.getArgument(0);
                String authorization = context.header("Authorization");
                if (authorization == null || !authorization.startsWith("Bearer ")) {
                    throw new SecurityException("missing bearer token");
                }
                String identity = authorization.substring("Bearer ".length());
                return new Principal(identity, 7L, List.of("user"), List.of(),
                        Instant.now(), "test");
            });
            return authentication;
        }

        @Bean
        TelemetryPort telemetryPort() {
            return mock(TelemetryPort.class);
        }

        @Bean
        McpGatewayAdapter mcpGatewayAdapter(AgentHostConnector connector) {
            return new McpGatewayAdapter(connector);
        }

        @Bean
        McpWebMvcTransportAdapter mcpTransport(
                ObjectMapper objectMapper,
                McpGatewayAdapter gatewayAdapter,
                AuthenticationPort authenticationPort,
                TelemetryPort telemetryPort) {
            return new McpWebMvcTransportAdapter(
                    objectMapper, gatewayAdapter, authenticationPort, telemetryPort,
                    8, Duration.ofMinutes(5));
        }

        @Bean
        RouterFunction<ServerResponse> mcpRouter(McpWebMvcTransportAdapter transport) {
            return transport.routerFunction();
        }

        @Bean
        FilterRegistrationBean<McpRequestContextFilter> mcpContextFilter() {
            FilterRegistrationBean<McpRequestContextFilter> registration =
                    new FilterRegistrationBean<>(new McpRequestContextFilter());
            registration.addUrlPatterns("/mcp/*");
            registration.setOrder(-100);
            return registration;
        }
    }
}
