package com.ai.gateway.domain.port;

import com.ai.gateway.domain.model.AdminAction;
import com.ai.gateway.domain.model.AclPolicyStatus;
import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.CapabilityVisibility;
import com.ai.gateway.domain.model.Principal;
import com.ai.gateway.domain.model.PolicySnapshot;

import java.util.List;

/**
 * 可见性鉴权、执行鉴权与控制面操作鉴权的端口。
 *
 * <p>规定运行时调用的鉴权分两趟执行：</p>
 * <ol>
 * <li><b>可见性鉴权</b>：决定某个能力能否进入检索与模型候选集。当前 Principal 未被
 * 授权的能力会被排除——其名称、描述与存在本身都不暴露。</li>
 * <li><b>执行鉴权</b>：在参数完全绑定后执行第二次鉴权检查，可能结合资源属性。</li>
 * </ol>
 *
 * <p>控制面（管理）操作由 {@link #authorizeAdmin(Principal, AdminAction)} 单独门控。</p>
 *
 * <p>默认拒绝（default deny）。策略异常、依赖超时或声明缺失都不得降级为放行。策略版本
 * 必须与目录快照协同发布，以确保运行期权限与能力一致。</p>
 *
 * <p>仅用于开发的 stub 可允许已鉴权用户调用已发布的只读能力。生产适配器必须加载显式
 * 策略；ACL 数据缺失或不健康时失效关闭（fail-closed）。</p>
 *
 * <p>实现此端口的适配器查询鉴权数据源（如 RBAC/ABAC/ReBAC 引擎或能力-角色 ACL 表）。
 * 该端口是纯粹的领域抽象，不依赖任何框架。</p>
 *
 * @see Principal
 * @see CapabilityManifest
 * @see AdminAction
 * @since 0.1.0
 */
public interface AuthorizationPort {

    /**
     * 原子地解析主体可见性及其所对应的策略纪元（epoch）。
     * Agent 请求路径必须固定持有该对象，而非组合多次独立读取。
     */
    default PolicySnapshot resolvePolicySnapshot(Principal principal) {
        return PolicySnapshot.from(resolveVisibility(principal));
    }

    /**
     * 返回一个绑定到当前策略纪元、按主体划分的可见性集合。
     * 未提供集合式索引的实现将失效关闭（fail closed）。
     */
    default CapabilityVisibility resolveVisibility(Principal principal) {
        return CapabilityVisibility.unavailable(currentPolicyEpoch());
    }

    /** 返回当前生效鉴权策略的单调递增纪元（epoch）。 */
    default long currentPolicyEpoch() {
        return 0L;
    }

    /**
     * 将候选能力列表过滤为仅对给定主体可见的能力（可见性鉴权第 1 趟）。
     *
     * <p>主体未被授权的能力会被移除，其名称、描述与存在本身都不会暴露给检索引擎或
     * LLM。若鉴权数据源不可用，采用 Fail Closed——不返回任何能力。</p>
     *
     * @param principal 已鉴权的调用方身份
     * @param candidates 已发布能力清单的完整列表
     * @return 过滤后的可见能力列表；永不为 {@code null}
     */
    List<CapabilityManifest> filterVisibleCapabilities(
            Principal principal, List<CapabilityManifest> candidates);

    /**
     * 判断给定主体是否被授权执行某个具体的能力版本（执行鉴权第 2 趟）。
     *
     * <p>该检查在参数完全绑定后执行，可能结合资源属性。默认拒绝：策略异常、依赖超时
     * 或声明缺失都不得降级为放行。</p>
     *
     * @param principal 已鉴权的调用方身份
     * @param capabilityId 能力标识
     * @param version 能力语义化版本
     * @return 若允许执行则为 {@code true}，否则为 {@code false}
     */
    boolean authorizeExecution(Principal principal, String capabilityId, String version);

    /**
     * 判断给定主体是否被授权执行某控制面管理操作（导入、审批、发布、回滚或下线）。
     *
     * <p>默认拒绝：策略异常、依赖超时或声明缺失都不得降级为放行。</p>
     *
     * @param principal 已鉴权的调用方身份
     * @param action 正在尝试的管理操作
     * @return 若操作被授权则为 {@code true}，否则为 {@code false}
     */
    boolean authorizeAdmin(Principal principal, AdminAction action);

    /** 返回当前生效的能力 ACL 缓存状态。 */
    AclPolicyStatus aclPolicyStatus();

    /**
     * 刷新 ACL 缓存
     *
     * <p>ACL 变更后调用，通知授权适配器重新加载数据库中的 ACL 条目。
     * 默认无操作，由持有 ACL 缓存的适配器覆盖实现。</p>
     *
     * @since 0.1.0
     */
    default void refreshAcl() {
        // 默认无操作
    }
}
