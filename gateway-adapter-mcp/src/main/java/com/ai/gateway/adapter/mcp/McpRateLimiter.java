package com.ai.gateway.adapter.mcp;

import com.ai.gateway.application.resilience.RateLimiterManager;

import java.util.Objects;

/**
 * MCP 专用入口限流。
 *
 * <p>当前使用统一 RateLimiterPort 的固定资源键，保证 Sentinel/Stub 两种部署
 * 模式都能复用现有基础设施。主体级保护由会话容量和单会话 in-flight 共同提供。</p>
 */
public final class McpRateLimiter {

    public static final String SSE = "mcp-sse";
    public static final String MESSAGE = "mcp-message";
    public static final String RESOLVE = "mcp-resolve";
    public static final String CALL = "mcp-call";
    /** 工具清单变更通知的广播资源键：目录抖动不应放大成会话风暴。 */
    public static final String NOTIFY = "mcp-notify";

    private static final String GLOBAL_KEY = "global";
    private final RateLimiterManager delegate;

    private McpRateLimiter(RateLimiterManager delegate) {
        this.delegate = delegate;
    }

    public static McpRateLimiter from(RateLimiterManager delegate) {
        return new McpRateLimiter(Objects.requireNonNull(delegate));
    }

    public static McpRateLimiter allowAll() {
        return new McpRateLimiter(null);
    }

    public boolean tryAcquire(String operation) {
        if (delegate == null) {
            return true;
        }
        return delegate.checkAndAcquire(operation, GLOBAL_KEY);
    }
}
