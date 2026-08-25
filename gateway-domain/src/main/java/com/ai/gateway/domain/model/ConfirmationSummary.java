package com.ai.gateway.domain.model;

import java.util.List;

/**
 * 控制面确认阶段向提交者展示的系统生成确认摘要。
 *
 * <p>规定清单通过全部 10 步自动校验后，系统生成至少包含以下内容的确认摘要：</p>
 *
 * <ul>
 * <li>能力 ID、版本与风险等级。</li>
 * <li>协议绑定摘要：接口名、方法、序列化方式。</li>
 * <li>模型可见字段列表（inputSchema 中的 MODEL 字段）。</li>
 * <li>Principal 注入字段列表（如 orgId）。</li>
 * <li>输出投影字段与脱敏规则。</li>
 * <li>所需权限字符串。</li>
 * <li>兼容性测试结果。</li>
 * <li>清单内容 SHA-256 摘要。</li>
 * </ul>
 *
 * <p>确认记录必须绑定到清单摘要、确认人、时间、环境与意见（确认或拒绝）。当清单内容
 * 变更时，旧确认自动失效。</p>
 *
 * <p>此简化流程仅适用于 READ_ONLY 能力。WRITE_LOW 与 WRITE_HIGH 仍需独立安全评审与
 * 双人审批。</p>
 *
 * @param capabilityId 能力标识
 * @param version 语义化版本
 * @param risk 风险等级
 * @param interfaceName 协议接口名
 * @param method 协议方法名
 * @param serialization 序列化方式
 * @param modelVisibleFields MODEL 来源字段路径列表
 * @param principalInjectedFields PRINCIPAL 来源字段路径列表
 * @param outputProjections 输出投影映射
 * @param redactions 输出脱敏规则
 * @param requiredPermissions 所需权限字符串
 * @param compatibilityTestResult 兼容性测试结果摘要
 * @param manifestSha256 清单内容的 SHA-256 摘要
 * @since 0.1.0
 */
public record ConfirmationSummary(
        String capabilityId,
        String version,
        RiskLevel risk,
        String interfaceName,
        String method,
        String serialization,
        List<String> modelVisibleFields,
        List<String> principalInjectedFields,
        List<ProjectionMapping> outputProjections,
        List<RedactionRule> redactions,
        List<String> requiredPermissions,
        String compatibilityTestResult,
        String manifestSha256
) {

    /**
     * 紧凑构造器，执行防御性拷贝。
     *
     * @param capabilityId 能力 ID
     * @param version 版本
     * @param risk 风险等级
     * @param interfaceName 接口名
     * @param method 方法名
     * @param serialization 序列化方式
     * @param modelVisibleFields 模型可见字段
     * @param principalInjectedFields Principal 注入字段
     * @param outputProjections 输出投影
     * @param redactions 脱敏规则
     * @param requiredPermissions 所需权限
     * @param compatibilityTestResult 兼容性测试结果
     * @param manifestSha256 清单 SHA-256 摘要
     */
    public ConfirmationSummary {
        java.util.Objects.requireNonNull(capabilityId, "capabilityId must not be null");
        java.util.Objects.requireNonNull(version, "version must not be null");
        java.util.Objects.requireNonNull(risk, "risk must not be null");
        modelVisibleFields = List.copyOf(modelVisibleFields);
        principalInjectedFields = List.copyOf(principalInjectedFields);
        outputProjections = List.copyOf(outputProjections);
        redactions = List.copyOf(redactions);
        requiredPermissions = List.copyOf(requiredPermissions);
    }
}
