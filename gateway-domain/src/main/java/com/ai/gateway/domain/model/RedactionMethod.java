package com.ai.gateway.domain.model;

/**
 * 字段级脱敏方法，应用于投影之后、公开出参 Schema 校验之前。
 *
 * <p>脱敏规则是声明式且确定性的，不涉及脚本或任意表达式。</p>
 *
 * @see RedactionRule
 * @see OutputContract
 * @since 0.1.0
 */
public enum RedactionMethod {
    /**
     * 对字段值做部分掩码（例如仅保留首尾字符）。具体掩码算法由网关以确定性方式实现。
     */
    PARTIAL_MASK,

    /**
     * 以单向哈希替换字段值。哈希算法由平台配置并确定性执行，清单无法自定义。
     */
    HASH,

    /**
     * 从输出中彻底删除该字段。该字段不会出现在投影结果中。
     */
    DELETE
}
