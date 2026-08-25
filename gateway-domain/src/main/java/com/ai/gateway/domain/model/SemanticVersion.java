package com.ai.gateway.domain.model;

import java.util.regex.Pattern;

/**
 * 能力清单的语义化版本字符串。
 *
 * <p>规定版本号规则：</p>
 * <ul>
 * <li>主版本升级：移除公开字段、收紧字段约束、变更语义或协议参数位置。</li>
 * <li>次版本升级：新增可选字段或兼容的出参字段。</li>
 * <li>修订版本升级：修改示例或描述而不改变选择语义。</li>
 * </ul>
 *
 * <p>任何对协议 Binding 的变更都需重新运行兼容性测试。给定环境下，每个
 * {@code metadata.id} 同一时刻只能存在一个默认可路由版本。</p>
 *
 * <p>本 record 仅校验 SemVer 格式（{@code MAJOR.MINOR.PATCH}），并不强制升级规则
 * —— 那些是兼容性分析期间的策略决策。</p>
 *
 * @param value 语义化版本字符串（如 {@code "1.0.0"}）
 * @since 0.1.0
 */
public record SemanticVersion(String value) {

    /**
     * 匹配 SemVer {@code MAJOR.MINOR.PATCH} 格式的正则，含可选的预发布与构建元数据后缀。
     */
    private static final Pattern SEMVER_PATTERN =
            Pattern.compile("^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)"
                    + "(?:-((?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*)"
                    + "(?:\\.(?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*))*)))?"
                    + "(?:\\+([0-9a-zA-Z-]+(?:\\.[0-9a-zA-Z-]+)*))?$");

    /**
     * 紧凑构造器，执行 SemVer 格式校验。
     *
     * @param value 语义化版本字符串
     * @throws NullPointerException 当 {@code value} 为 null 时
     * @throws IllegalArgumentException 当 {@code value} 不符合 SemVer 格式时
     */
    public SemanticVersion {
        java.util.Objects.requireNonNull(value, "version value must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("version value must not be blank");
        }
        if (!SEMVER_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "version value must conform to SemVer MAJOR.MINOR.PATCH format: " + value);
        }
    }
}
