package com.ai.gateway.adapter.mcp;

/**
 * MCP 入口的安全能力档案。
 *
 * <p>READ_ONLY 是默认模式。NO_AUTH 仅供本地开发跳过传输认证，并且仍保持只读。
 * TRUSTED_CONFIRMATION 只适用于已经由 Host
 * 注册并提供独立确认通道的客户端；它不是通用 MCP Client 的默认能力。</p>
 */
public enum McpSecurityMode {
    DISABLED(false),
    READ_ONLY(false),
    NO_AUTH(false),
    TRUSTED_CONFIRMATION(true);

    private final boolean allowWritePrepare;

    McpSecurityMode(boolean allowWritePrepare) {
        this.allowWritePrepare = allowWritePrepare;
    }

    public boolean allowWritePrepare() {
        return allowWritePrepare;
    }

    public static McpSecurityMode parse(String value) {
        if (value == null || value.isBlank()) {
            return READ_ONLY;
        }
        try {
            return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unsupported MCP security mode: " + value, e);
        }
    }
}
