package com.ai.gateway.adapter.mcp;

import com.ai.gateway.domain.model.RequestContext;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpClientTrustRegistryTest {

    @Test
    void onlyRegisteredBearerFingerprintCanEnableHostConfirmation() {
        String token = "trusted-token";
        McpClientTrustRegistry registry = new McpClientTrustRegistry(List.of(
                new McpClientTrustProfile("desktop-agent",
                        McpClientTrustRegistry.sha256(token),
                        McpClientTrustProfile.TokenAssurance.HIGH,
                        McpClientTrustProfile.ConfirmationChannel.HOST_UI,
                        true, Instant.parse("2026-08-21T00:00:00Z"))),
                Clock.fixed(Instant.parse("2026-08-20T00:00:00Z"), ZoneOffset.UTC));

        RequestContext context = context(token, "desktop-agent");

        assertThat(registry.isTrusted(context)).isTrue();
        assertThat(registry.isTrusted(context("unregistered-token", "desktop-agent")))
                .isFalse();
    }

    @Test
    void clientIdHeaderIsOnlyAConsistencyCheckAndExpiredProfileFailsClosed() {
        String token = "trusted-token";
        McpClientTrustRegistry registry = new McpClientTrustRegistry(List.of(
                new McpClientTrustProfile("desktop-agent",
                        McpClientTrustRegistry.sha256(token),
                        McpClientTrustProfile.TokenAssurance.HIGH,
                        McpClientTrustProfile.ConfirmationChannel.HOST_UI,
                        true, Instant.parse("2026-08-19T00:00:00Z"))),
                Clock.fixed(Instant.parse("2026-08-20T00:00:00Z"), ZoneOffset.UTC));

        assertThat(registry.isTrusted(context(token, "other-agent"))).isFalse();
        assertThat(registry.isTrusted(context(token, null))).isFalse();
    }

    private static RequestContext context(String token, String clientId) {
        Map<String, String> headers = clientId == null
                ? Map.of("Authorization", "Bearer " + token)
                : Map.of("Authorization", "Bearer " + token,
                        "Mcp-Client-Id", clientId);
        return new RequestContext(headers, Map.of(), Map.of(), null);
    }
}
