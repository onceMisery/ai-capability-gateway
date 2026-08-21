package com.ai.gateway.adapter.rest;

import com.ai.gateway.domain.model.ArgumentBinding;
import com.ai.gateway.domain.model.ArgumentSource;
import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.DeadlineBudget;
import com.ai.gateway.domain.model.InvocationRequest;
import com.ai.gateway.domain.model.Protocol;
import com.ai.gateway.domain.model.ProtocolBinding;
import com.ai.gateway.domain.model.SystemContext;
import com.ai.gateway.domain.port.ManifestRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RestInvocationAdapterTest {

    @Test
    void buildsGetRequestFromPathAndQueryBindings() throws Exception {
        ManifestRepository manifests = mock(ManifestRepository.class);
        RestHttpClient httpClient = mock(RestHttpClient.class);
        ProtocolBinding binding = binding("GET", "/orders/{orderNo}");
        when(manifests.findByIdAndVersion("orders.query", "1.0.0"))
                .thenReturn(Optional.of(manifest(binding)));
        when(httpClient.send(org.mockito.ArgumentMatchers.any(HttpRequest.class),
                org.mockito.ArgumentMatchers.anyLong()))
                .thenAnswer(invocation -> {
                    HttpRequest request = invocation.getArgument(0);
                    assertThat(request.uri()).isEqualTo(
                            URI.create("https://orders.internal/orders/SO-1?locale=zh-CN"));
                    assertThat(request.method()).isEqualTo("GET");
                    assertThat(request.headers().firstValue("X-Trace-Id"))
                            .contains("trace-1");
                    return new RestHttpResponse(200, "{\"status\":\"PAID\"}",
                            Map.of("content-type", "application/json"));
                });

        RestInvocationAdapter adapter = new RestInvocationAdapter(
                manifests,
                key -> URI.create("https://orders.internal"),
                httpClient,
                new ObjectMapper());

        var result = adapter.invoke(request("orders.query", List.of("SO-1")));

        assertThat(result.errorCode()).isNull();
        assertThat(result.jsonData()).isEqualTo(Map.of("status", "PAID"));
    }

    @Test
    void mapsHttpTimeoutToProviderTimeoutWithoutLeakingException() throws Exception {
        ManifestRepository manifests = mock(ManifestRepository.class);
        RestHttpClient httpClient = mock(RestHttpClient.class);
        when(manifests.findByIdAndVersion("orders.query", "1.0.0"))
                .thenReturn(Optional.of(manifest(binding("GET", "/orders/{orderNo}"))));
        when(httpClient.send(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong()))
                .thenThrow(new java.net.http.HttpTimeoutException("secret endpoint details"));

        RestInvocationAdapter adapter = new RestInvocationAdapter(
                manifests,
                key -> URI.create("https://orders.internal"),
                httpClient,
                new ObjectMapper());

        var result = adapter.invoke(request("orders.query", List.of("SO-1")));

        assertThat(result.errorCode()).isEqualTo(com.ai.gateway.domain.model.ErrorCode.PROVIDER_TIMEOUT);
        assertThat(result.errorMessage()).isEqualTo("REST provider timed out");
        assertThat(result.errorMessage()).doesNotContain("secret");
    }

    private static InvocationRequest request(String id, List<Object> args) {
        return new InvocationRequest(id, "1.0.0", "digest",
                new DeadlineBudget(1000, 321), null,
                new SystemContext("trace-1", System.currentTimeMillis() + 321,
                        null, "zh-CN"),
                args);
    }

    private static ProtocolBinding binding(String method, String path) {
        return new ProtocolBinding(Protocol.REST, "orders", path, null, null,
                method, List.of("java.lang.String"), "application/json",
                List.of(new ArgumentBinding(0, "orderNo", "java.lang.String",
                        ArgumentSource.MODEL, "/orderNo", null, null, null)),
                Map.of());
    }

    private static CapabilityManifest manifest(ProtocolBinding binding) {
        CapabilityManifest.Metadata metadata = new CapabilityManifest.Metadata(
                "orders.query", "1.0.0",
                new CapabilityManifest.Owner("orders", "orders@example.com"),
                List.of("orders"));
        CapabilityManifest.Spec spec = new CapabilityManifest.Spec(
                "query", "query", new CapabilityManifest.Examples(List.of(), List.of(), List.of()),
                com.ai.gateway.domain.model.RiskLevel.READ_ONLY, Map.of(), null, binding,
                mock(com.ai.gateway.domain.model.OutputContract.class),
                mock(com.ai.gateway.domain.model.ResiliencePolicy.class));
        return new CapabilityManifest("gateway.ai/v1", "Capability", metadata, spec);
    }
}
