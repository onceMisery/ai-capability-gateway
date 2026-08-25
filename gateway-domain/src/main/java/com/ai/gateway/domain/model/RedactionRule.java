package com.ai.gateway.domain.model;

/**
 * 应用于投影后输出字段的声明式脱敏规则。
 *
 * <p>规定在投影之后、公开出参 Schema 校验之前，网关应用字段级脱敏。脱敏是确定性的，
 * 不涉及脚本或任意表达式。</p>
 *
 * <p>契约校验器必须验证：</p>
 * <ul>
 * <li>脱敏路径存在于投影输出中。</li>
 * <li>脱敏结果与公开 Schema 一致（如 {@code DELETE} 规则不得删除必需字段）。</li>
 * </ul>
 *
 * @param path 待脱敏字段的 JSON Pointer（如 {@code "/customerName"}）
 * @param method 要应用的脱敏方法
 * @since 0.1.0
 */
public record RedactionRule(String path, RedactionMethod method) {

    /**
     * 紧凑构造器，执行 null 检查。
     *
     * @param path 字段的 JSON Pointer
     * @param method 脱敏方法
     */
    public RedactionRule {
        java.util.Objects.requireNonNull(path, "path must not be null");
        java.util.Objects.requireNonNull(method, "method must not be null");
    }
}
