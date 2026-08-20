package com.ai.gateway.bootstrap.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayPropertiesBindingTest {

    @Test
    void bindsRuntimeLimitsProvidersAndCacheStatusFromOnePropertyOwner() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.ofEntries(
                Map.entry("gateway.environment", "staging"),
                Map.entry("gateway.max-request-size-bytes", "131072"),
                Map.entry("gateway.max-response-bytes", "2097152"),
                Map.entry("gateway.default-timeout-ms", "20000"),
                Map.entry("gateway.secret-file-path", "C:/run/secrets/gateway.properties"),
                Map.entry("gateway.auth.provider", "sa-token"),
                Map.entry("gateway.auth.sa-token.jwt-secret-key", "test-secret"),
                Map.entry("gateway.auth.console-admin.username", "operator"),
                Map.entry("gateway.auth.console-admin.password", "operator-password"),
                Map.entry("gateway.cache.provider", "redis"),
                Map.entry("gateway.ratelimit.provider", "sentinel"),
                Map.entry("gateway.redis.address", "redis://cache:6379"),
                Map.entry("gateway.redis.password", "redis-secret"),
                Map.entry("gateway.redis.database", "4"),
                Map.entry("gateway.redis.snapshot.local-ttl-seconds", "45"),
                Map.entry("gateway.audit.batch-size", "75"),
                Map.entry("gateway.audit.batch-wait-millis", "7"),
                Map.entry("gateway.snapshot.max-lag-millis", "45000"),
                Map.entry("gateway.sentinel.global-qps", "2500"),
                Map.entry("gateway.sentinel.llm-qps", "25"),
                Map.entry("gateway.sentinel.llm-max-queueing-ms", "2500"),
                Map.entry("gateway.llm.endpoint", "http://llm"),
                Map.entry("gateway.llm.api-key", "llm-key"),
                Map.entry("gateway.llm.model", "model"),
                Map.entry("gateway.operation.confirmation-secret", "confirmation-secret"),
                Map.entry("gateway.agent.tool-ref-current-key-id", "key-7"),
                 Map.entry("gateway.agent.tool-ref-secret", "agent-secret"),
                 Map.entry("gateway.agent.tool-ref-ttl-seconds", "90"),
                 Map.entry("gateway.agent.resolve-timeout-ms", "75"),
                 Map.entry("gateway.agent.pending-confirmation-max-entries", "2000"),
                Map.entry("gateway.agent.resolve-max-concurrent", "24"),
                Map.entry("gateway.agent.catalog-max-capabilities", "5000"),
                Map.entry("gateway.agent.catalog-max-index-bytes", "33554432"),
                Map.entry("gateway.agent.catalog-max-process-memory-bytes", "268435456"),
                Map.entry("gateway.agent.catalog-build-timeout-ms", "2500"),
                Map.entry("gateway.agent.catalog-lease-hold-timeout-ms", "800"),
                Map.entry("gateway.agent.catalog-io-max-rows", "7500"),
                Map.entry("gateway.agent.catalog-io-query-timeout-ms", "2200"),
                Map.entry("gateway.agent.catalog-io-max-payload-bytes", "67108864"),
                Map.entry("gateway.agent.mcp-max-sessions", "128"),
                Map.entry("gateway.agent.mcp-session-idle-seconds", "600")
        ));

        GatewayProperties properties = new Binder(source)
                .bind("gateway", Bindable.of(GatewayProperties.class))
                .orElseThrow(() -> new IllegalStateException("gateway properties not bound"));

        assertThat(properties.getEnvironment()).isEqualTo("staging");
        assertThat(properties.getMaxRequestSizeBytes()).isEqualTo(131072);
        assertThat(properties.getMaxResponseBytes()).isEqualTo(2097152L);
        assertThat(properties.getDefaultTimeoutMs()).isEqualTo(20000);
        assertThat(properties.getSecretFilePath()).isEqualTo("C:/run/secrets/gateway.properties");
        assertThat(properties.getAuth().getProvider()).isEqualTo("sa-token");
        assertThat(properties.getAuth().getSaToken().getJwtSecretKey()).isEqualTo("test-secret");
        assertThat(properties.getAuth().getConsoleAdmin().getUsername()).isEqualTo("operator");
        assertThat(properties.getCache().getProvider()).isEqualTo("redis");
        assertThat(properties.getRatelimit().getProvider()).isEqualTo("sentinel");
        assertThat(properties.getRedis().getAddress()).isEqualTo("redis://cache:6379");
        assertThat(properties.getRedis().getPassword()).isEqualTo("redis-secret");
        assertThat(properties.getRedis().getDatabase()).isEqualTo(4);
        assertThat(properties.getRedis().getSnapshot().getLocalTtlSeconds()).isEqualTo(45);
        assertThat(properties.getAudit().getBatchSize()).isEqualTo(75);
        assertThat(properties.getAudit().getBatchWaitMillis()).isEqualTo(7);
        assertThat(properties.getSnapshot().getMaxLagMillis()).isEqualTo(45000);
        assertThat(properties.getSentinel().getGlobalQps()).isEqualTo(2500d);
        assertThat(properties.getLlm().getEndpoint()).isEqualTo("http://llm");
        assertThat(properties.getOperation().getConfirmationSecret()).isEqualTo("confirmation-secret");
        assertThat(properties.getAgent().getToolRefCurrentKeyId()).isEqualTo("key-7");
        assertThat(properties.getAgent().getToolRefSecret()).isEqualTo("agent-secret");
        assertThat(properties.getAgent().getToolRefTtlSeconds()).isEqualTo(90L);
        assertThat(properties.getAgent().getResolveTimeoutMs()).isEqualTo(75L);
        assertThat(properties.getAgent().getPendingConfirmationMaxEntries()).isEqualTo(2000);
        assertThat(properties.getAgent().getResolveMaxConcurrent()).isEqualTo(24);
        assertThat(properties.getAgent().getCatalogMaxCapabilities()).isEqualTo(5000);
        assertThat(properties.getAgent().getCatalogMaxIndexBytes()).isEqualTo(33_554_432L);
        assertThat(properties.getAgent().getCatalogMaxProcessMemoryBytes()).isEqualTo(268_435_456L);
        assertThat(properties.getAgent().getCatalogBuildTimeoutMs()).isEqualTo(2500L);
        assertThat(properties.getAgent().getCatalogLeaseHoldTimeoutMs()).isEqualTo(800L);
        assertThat(properties.getAgent().getCatalogIoMaxRows()).isEqualTo(7500);
        assertThat(properties.getAgent().getCatalogIoQueryTimeoutMs()).isEqualTo(2200L);
        assertThat(properties.getAgent().getCatalogIoMaxPayloadBytes()).isEqualTo(67_108_864L);
        assertThat(properties.getAgent().getMcpMaxSessions()).isEqualTo(128);
        assertThat(properties.getAgent().getMcpSessionIdleSeconds()).isEqualTo(600L);
    }
}
