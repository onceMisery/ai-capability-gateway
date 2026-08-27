package com.ai.gateway.adapter.a2a;

/**
 * A2A 运行模式。
 *
 * <p>入站（Server）与出站（Client）是两个独立的暴露面，风险也不同：入站决定「谁能通过 A2A
 * 使用本网关的能力」，出站决定「本网关能把请求转发给哪些外部 Agent」。用一个枚举把两者的开关
 * 显式组合，避免出现「以为只开了入站、实际把出站也打开了」这类配置歧义。</p>
 *
 * @author cmiracle@163.com
 * @since 0.1.0
 */
public enum A2aMode {

    /** 完全关闭：既不接受 A2A 入站请求，也不发起 A2A 出站调用。 */
    DISABLED(false, false),

    /** 仅入站：对外提供 AgentCard 与 Task 处理，不主动调用外部 Agent。 */
    SERVER_ONLY(true, false),

    /** 仅出站：把外部 Agent 当作一种协议后端调用，不对外暴露本网关的 A2A 面。 */
    CLIENT_ONLY(false, true),

    /** 双向：入站与出站同时启用。 */
    FULL(true, true);

    private final boolean serverEnabled;
    private final boolean clientEnabled;

    A2aMode(boolean serverEnabled, boolean clientEnabled) {
        this.serverEnabled = serverEnabled;
        this.clientEnabled = clientEnabled;
    }

    /**
     * @return 是否启用入站服务端（AgentCard 与 Task 处理）
     */
    public boolean serverEnabled() {
        return serverEnabled;
    }

    /**
     * @return 是否启用出站客户端（调用外部 Agent）
     */
    public boolean clientEnabled() {
        return clientEnabled;
    }

    /**
     * 宽松解析配置值。
     *
     * <p>无法识别的值一律归为 {@link #DISABLED} 而不是抛异常：A2A 是新增暴露面，
     * 配置写错时「什么都不开」是唯一安全的默认结果。真正需要拒绝启动的组合
     * （如启用了却没配 public-url）由生产配置校验器负责，那里能给出明确的错误信息。</p>
     *
     * @param value 配置值，允许为 {@code null}
     * @return 解析结果；无法识别时返回 {@link #DISABLED}
     */
    public static A2aMode from(String value) {
        if (value == null || value.isBlank()) {
            return DISABLED;
        }
        String normalized = value.trim().toUpperCase(java.util.Locale.ROOT).replace('-', '_');
        for (A2aMode mode : values()) {
            if (mode.name().equals(normalized)) {
                return mode;
            }
        }
        return DISABLED;
    }
}
