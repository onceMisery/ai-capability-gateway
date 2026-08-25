package com.ai.gateway.domain.model;

import java.util.List;

/**
 * 给定环境下活动能力目录的不可变快照，由控制面原子发布。
 *
 * <p>规定发布必须在单一数据库事务内完成：</p>
 * <ol>
 * <li>校验目标版本仍为 APPROVED。</li>
 * <li>生成单调递增的 {@code snapshotVersion}。</li>
 * <li>冻结该环境下全部活动能力与策略引用。</li>
 * <li>将新快照标记为当前版本。</li>
 * <li>写入发布审计与通知事件。</li>
 * </ol>
 *
 * <p>各实例收到通知后，从 PostgreSQL 加载快照、构建检索索引并校验摘要。成功则原子替换
 * 内存引用；失败则保留旧快照，并在超过最大滞后时间后退出就绪状态。</p>
 *
 * <p>每个请求都绑定到处理开始时生效的快照版本。回滚是把历史快照内容拷贝到新快照版本，
 * 不修改历史。</p>
 *
 * <p>{@code policyRef} 引用发布时生效的鉴权策略版本。{@code digest} 是内容 SHA-256 摘要，
 * 实例加载后用以校验快照完整性。</p>
 *
 * @param snapshotVersion 单调递增的快照版本
 * @param environment 目标环境（如 "production"）
 * @param capabilities 已发布的能力清单列表
 * @param policyRef 鉴权策略引用
 * @param digest 内容 SHA-256 摘要
 * @since 0.1.0
 */
public record CatalogSnapshot(
        long snapshotVersion,
        String environment,
        List<CapabilityManifest> capabilities,
        String policyRef,
        String digest
) {

    /**
     * 紧凑构造器，执行防御性拷贝与 null 检查。
     *
     * @param snapshotVersion 快照版本
     * @param environment 环境
     * @param capabilities 能力清单
     * @param policyRef 策略引用
     * @param digest 摘要
     */
    public CatalogSnapshot {
        java.util.Objects.requireNonNull(environment, "environment must not be null");
        java.util.Objects.requireNonNull(capabilities, "capabilities must not be null");
        java.util.Objects.requireNonNull(digest, "digest must not be null");
        capabilities = List.copyOf(capabilities);
    }
}
