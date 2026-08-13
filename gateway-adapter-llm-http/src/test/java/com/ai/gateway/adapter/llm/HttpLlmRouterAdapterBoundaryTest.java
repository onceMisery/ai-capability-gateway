package com.ai.gateway.adapter.llm;

import com.ai.gateway.domain.model.ErrorCode;
import com.ai.gateway.domain.port.LlmRouterPort;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpLlmRouterAdapterBoundaryTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void boundedReaderAcceptsBodyExactlyAtLimit() {
        byte[] body = "12345678".getBytes(StandardCharsets.UTF_8);

        String result = HttpLlmRouterAdapter.readBoundedBody(
                new ByteArrayInputStream(body), body.length);

        assertThat(result).isEqualTo("12345678");
    }

    @Test
    void boundedReaderRejectsBodyOneByteOverLimit() {
        byte[] body = "123456789".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> HttpLlmRouterAdapter.readBoundedBody(
                new ByteArrayInputStream(body), 8))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("maximum");
    }

    @Test
    void providerFailureIsReportedAsLlmUnavailableNotNoMatch() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/chat", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        HttpLlmRouterAdapter adapter = new HttpLlmRouterAdapter(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/chat",
                "test-key", "test-model", 0.0, 64,
                new LlmRequestBuilder(), new LlmResponseParser(),
                new PromptTemplateRegistry(), 1024);
        LlmRouterPort.LlmCandidate candidate = new LlmRouterPort.LlmCandidate(
                "cap_1", "Query", "Query data", List.of(), List.of(), List.of(), Map.of());

        assertThatThrownBy(() -> adapter.route("query", List.of(candidate)))
                .isInstanceOf(LlmRouterPort.LlmRoutingException.class)
                .extracting(error -> ((LlmRouterPort.LlmRoutingException) error).errorCode())
                .isEqualTo(ErrorCode.LLM_UNAVAILABLE);
    }

    @Test
    void providerRateLimitIsReportedAsRateLimited() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/chat", exchange -> {
            exchange.sendResponseHeaders(429, -1);
            exchange.close();
        });
        server.start();
        HttpLlmRouterAdapter adapter = new HttpLlmRouterAdapter(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/chat",
                "test-key", "test-model", 0.0, 64,
                new LlmRequestBuilder(), new LlmResponseParser(),
                new PromptTemplateRegistry(), 1024);
        LlmRouterPort.LlmCandidate candidate = new LlmRouterPort.LlmCandidate(
                "cap_1", "Query", "Query data", List.of(), List.of(), List.of(), Map.of());

        assertThatThrownBy(() -> adapter.route("query", List.of(candidate)))
                .isInstanceOf(LlmRouterPort.LlmRoutingException.class)
                .extracting(error -> ((LlmRouterPort.LlmRoutingException) error).errorCode())
                .isEqualTo(ErrorCode.RATE_LIMITED);
    }
}
