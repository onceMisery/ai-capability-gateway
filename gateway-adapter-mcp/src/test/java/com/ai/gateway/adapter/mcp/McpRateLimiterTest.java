package com.ai.gateway.adapter.mcp;

import com.ai.gateway.application.resilience.RateLimiterManager;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class McpRateLimiterTest {

    @Test
    void delegatesToDedicatedMcpDimensionAndGlobalKey() {
        McpRateLimiter limiter = McpRateLimiter.from(
                new RateLimiterManager((dimension, key, permits) ->
                        "mcp-call".equals(dimension) && "global".equals(key)));

        assertThat(limiter.tryAcquire(McpRateLimiter.CALL)).isTrue();
        assertThat(limiter.tryAcquire(McpRateLimiter.RESOLVE)).isFalse();
    }
}
