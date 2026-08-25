package com.ai.gateway.domain.model;

/**
 * 具名、可复用的信封配置档案，用于在不同能力间标准化统一响应的解包配置。
 *
 * <p>要求每种统一响应结构类型都注册为标准信封档案，并在兼容性测试中针对真实 Provider
 * 校验。这避免了照搬通用示例的 {@code /data} 路径与数值成功码，而实际平台使用
 * {@code /value} 与字符串成功码的错误陷阱。</p>
 *
 * @param name 档案名（如 "platform-standard"）
 * @param envelopeConfig 信封配置
 * @param description 对响应结构的可读描述
 * @since 0.1.0
 */
public record EnvelopeProfile(
        String name,
        EnvelopeConfig envelopeConfig,
        String description
) {

    /**
     * 紧凑构造器，执行 null 检查。
     *
     * @param name 档案名
     * @param envelopeConfig 信封配置
     * @param description 可读描述
     */
    public EnvelopeProfile {
        java.util.Objects.requireNonNull(name, "name must not be null");
        java.util.Objects.requireNonNull(envelopeConfig, "envelopeConfig must not be null");
        java.util.Objects.requireNonNull(description, "description must not be null");
    }
}
