package com.ai.gateway.domain.model;

import java.util.List;

/**
 * 针对结构、语义、安全与兼容性规则校验 ProtocolBinding 或清单的结果。
 *
 * <p>适配器端口的 {@code validate} 方法返回 {@code ValidationReport}。清单导入的完整
 * 10 步校验流水线即在此定义。</p>
 *
 * <p>仅当 {@code errors} 为空时，报告才视为有效。警告仅为提示性信息，不阻断发布。</p>
 *
 * @param valid 校验是否通过（无错误）
 * @param errors 校验错误列表；有效时为空
 * @param warnings 校验警告列表；不阻断流程
 * @since 0.1.0
 */
public record ValidationReport(
        boolean valid,
        List<String> errors,
        List<String> warnings
) {

    /**
     * 紧凑构造器，执行防御性拷贝。
     *
     * @param valid 校验是否通过
     * @param errors 错误列表
     * @param warnings 警告列表
     */
    public ValidationReport {
        java.util.Objects.requireNonNull(errors, "errors must not be null");
        java.util.Objects.requireNonNull(warnings, "warnings must not be null");
        errors = List.copyOf(errors);
        warnings = List.copyOf(warnings);
    }

    /**
     * 返回一份无错误、无警告的有效报告。
     *
     * @return 有效的空报告
     */
    public static ValidationReport success() {
        return new ValidationReport(true, List.of(), List.of());
    }

    /**
     * 返回一份含给定错误、无警告的无效报告。
     *
     * @param errors 校验错误
     * @return 无效报告
     */
    public static ValidationReport failure(List<String> errors) {
        return new ValidationReport(false, errors, List.of());
    }
}
