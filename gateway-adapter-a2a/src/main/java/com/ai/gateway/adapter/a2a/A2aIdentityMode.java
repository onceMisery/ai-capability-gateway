package com.ai.gateway.adapter.a2a;

import java.util.Locale;

/**
 * A2A 入站请求的身份来源模式。
 *
 * <p>A2A 是 Agent 之间的协议，因此「请求代表谁」必须在协议之外确定。本枚举只有两个合法取值，
 * 并且刻意排除了第三种在实现上很自然、在安全上不可接受的做法：<b>从 A2A 消息体里读取
 * {@code orgId} / {@code userId}</b>。消息体由对端构造，一旦允许从中取身份，
 * 任何注册过的 peer 都能声明自己代表任意租户的任意用户，租户隔离就整体失效。</p>
 *
 * @author cmiracle@163.com
 * @since 0.1.0
 */
public enum A2aIdentityMode {

    /**
     * 代理调用（默认、推荐）：peer 携带最终用户的凭据，网关按该凭据解析出
     * {@code Principal}，受信参数仍由 {@code ArgumentSource.PRINCIPAL} 注入。
     *
     * <p>因为身份来自真实凭据而非 peer 的声明，所以本模式允许在 peer 受信且具备独立确认通道时
     * 走到 {@code WRITE_LOW} 的两阶段写。</p>
     */
    ON_BEHALF_OF(true),

    /**
     * 服务账号：peer 以固定的服务身份调用，{@code orgId} 由网关侧注册表配置，
     * 能力范围由显式白名单限定。
     *
     * <p>本模式下没有最终用户，也就没有任何人能承担「确认」这个动作，
     * 因此<b>恒为只读</b>——把写操作的确认责任交给一个没有用户的服务账号，
     * 等于把两阶段写降级成一阶段写。</p>
     */
    SERVICE_ACCOUNT(false);

    private final boolean writeEligible;

    A2aIdentityMode(boolean writeEligible) {
        this.writeEligible = writeEligible;
    }

    /**
     * @return 本模式下是否<b>可能</b>走到需要确认的写操作；{@code false} 表示恒为只读
     */
    public boolean writeEligible() {
        return writeEligible;
    }

    /**
     * 宽松解析配置值。
     *
     * <p>无法识别的值归为 {@link #SERVICE_ACCOUNT}：它是两者中权限更窄的一个（恒只读），
     * 配置错误时落在更窄的一侧才符合失效关闭。</p>
     *
     * @param value 配置值，允许为 {@code null}
     * @return 解析结果；无法识别时返回 {@link #SERVICE_ACCOUNT}
     */
    public static A2aIdentityMode from(String value) {
        if (value == null || value.isBlank()) {
            return SERVICE_ACCOUNT;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        for (A2aIdentityMode mode : values()) {
            if (mode.name().equals(normalized)) {
                return mode;
            }
        }
        return SERVICE_ACCOUNT;
    }
}
