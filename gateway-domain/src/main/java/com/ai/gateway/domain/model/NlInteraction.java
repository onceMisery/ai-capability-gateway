package com.ai.gateway.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 多轮自然语言路由的澄清交互会话。
 *
 * <p>规定当模型返回澄清决策时，网关存储一条短时效交互记录，包含：</p>
 *
 * <ul>
 * <li>{@code interactionId} - 唯一会话标识。</li>
 * <li>{@code principalDigest} - 请求方 Principal 的摘要。</li>
 * <li>{@code snapshotVersion} - 固定的目录快照版本。</li>
 * <li>{@code candidateCapabilityIds} - 候选能力 ID/版本集合。</li>
 * <li>{@code confirmedParams} - 已确认的非敏感参数。</li>
 * <li>{@code pendingFields} - 仍需用户输入的字段。</li>
 * <li>{@code expiresAt} - 简短的过期时间。</li>
 * </ul>
 *
 * <p>后续回答只能补充缺失信息，或在原候选集内消歧。网关必须检测意图跳转：若用户回复
 * 触发 NO_MATCH，或选择了原候选集之外的别名，当前的 interactionId 立即失效，必须完整
 * 重启路由流水线——不得继承任何旧的授权、候选集或快照。</p>
 *
 * <p>Principal 变更、会话过期、能力下线或策略变更也会强制重新开始。</p>
 *
 * @param interactionId 唯一交互标识
 * @param principalDigest 请求方 Principal 的摘要
 * @param snapshotVersion 固定的目录快照版本
 * @param candidateCapabilityIds 候选能力标识
 * @param confirmedParams 已确认的非敏感参数
 * @param pendingFields 等待用户输入的字段
 * @param expiresAt 交互过期时间
 * @since 0.1.0
 */
public record NlInteraction(
        String interactionId,
        String principalDigest,
        long snapshotVersion,
        List<String> candidateCapabilityIds,
        Map<String, Object> confirmedParams,
        List<String> pendingFields,
        Instant expiresAt
) {

    /**
     * 紧凑构造器，执行防御性拷贝与 null 检查。
     *
     * @param interactionId 交互 ID
     * @param principalDigest 主体摘要
     * @param snapshotVersion 快照版本
     * @param candidateCapabilityIds 候选 ID 列表
     * @param confirmedParams 已确认参数
     * @param pendingFields 待填字段
     * @param expiresAt 过期时间
     */
    public NlInteraction {
        java.util.Objects.requireNonNull(interactionId, "interactionId must not be null");
        java.util.Objects.requireNonNull(principalDigest, "principalDigest must not be null");
        java.util.Objects.requireNonNull(candidateCapabilityIds, "candidateCapabilityIds must not be null");
        java.util.Objects.requireNonNull(confirmedParams, "confirmedParams must not be null");
        java.util.Objects.requireNonNull(pendingFields, "pendingFields must not be null");
        java.util.Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        candidateCapabilityIds = List.copyOf(candidateCapabilityIds);
        confirmedParams = Map.copyOf(confirmedParams);
        pendingFields = List.copyOf(pendingFields);
    }

    /**
     * 返回该交互是否已过期。
     *
     * @param now 当前时间
     * @return 当当前时间晚于 {@code expiresAt} 时为 {@code true}
     */
    public boolean isExpired(Instant now) {
        return now.isAfter(expiresAt);
    }
}
