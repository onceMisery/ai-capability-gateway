package com.ai.gateway.domain.port;

import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.CatalogSnapshot;
import com.ai.gateway.domain.model.SnapshotSummary;

import java.util.List;
import java.util.Optional;

/**
 * 加载与查询能力目录快照的端口。
 *
 * <p>规定发布必须在单一数据库事务内完成，生成单调递增的 {@code snapshotVersion}。每个
 * 运行时实例收到发布通知后，从 PostgreSQL 加载快照、构建检索索引并校验摘要。成功后原子
 * 替换内存引用。</p>
 *
 * <p>每个请求都绑定到处理开始时生效的快照版本。回滚是把历史快照内容拷贝到新快照版本，
 * 不修改历史。</p>
 *
 * <p>实现此端口的适配器通常从 PostgreSQL 读取并将快照缓存在内存中。该端口是纯粹的
 * 领域抽象，不依赖任何框架。</p>
 *
 * @see CatalogSnapshot
 * @see CapabilityManifest
 * @since 0.1.0
 */
public interface CatalogPort {

    /**
     * 串行化某环境的控制面生命周期变更。
     * 持久化适配器会获取一个事务作用域的锁；测试与内存适配器可保留默认的无操作实现。
     *
     * @param environment 正在变更的环境
     */
    default void lockEnvironmentForPublication(String environment) {
        // 无共享事务存储的适配器无操作。
    }

    /**
     * 保留下一个全局唯一的快照版本。
     *
     * <p>持久化适配器必须使用数据库序列或等价的原子分配器。调用方不得通过读取当前快照
     * 再加一的方式推导版本。</p>
     *
     * @return 单调递增的快照版本
     */
    long reserveSnapshotVersion();

    /**
     * 加载给定环境的当前活动快照。
     *
     * <p>规定：返回被标记为该环境当前版本的快照。快照一旦发布即不可变。</p>
     *
     * @param environment 目标环境（如 "production"）
     * @return 当前目录快照；永不为 {@code null}
     */
    CatalogSnapshot loadCurrentSnapshot(String environment);

    /**
     * 按版本号加载特定的历史快照。
     *
     * <p>规定：回滚是把历史快照内容拷贝到新快照版本，不修改历史。本方法用于检查或回滚
     * 目的，检索原始历史快照。</p>
     *
     * @param snapshotVersion 单调递增的快照版本
     * @return 该版本的目录快照；永不为 {@code null}
     */
    CatalogSnapshot loadSnapshot(long snapshotVersion);

    /**
     * 在当前快照中按 ID 与版本查找特定能力。
     *
     * <p>返回的清单是确切的发布内容，可由其 SHA-256 摘要验证。相同的 {@code id + version}
     * 内容不可被覆盖。</p>
     *
     * @param capabilityId 全局稳定的能力标识
     * @param version 语义化版本字符串
     * @return 匹配的能力清单，未找到时为 empty
     */
    Optional<CapabilityManifest> findCapability(String capabilityId, String version);

    /**
     * 持久化一个新的目录快照并将其标记为 ACTIVE。
     *
     * <p>同一环境先前的 ACTIVE 快照会被标记为 SUPERSEDED。该操作只更改快照表；生命周期、
     * 审计与 Outbox 的副作用是应用事务拥有的显式操作。</p>
     *
     * @param snapshot 待持久化的快照
     */
    void saveSnapshot(CatalogSnapshot snapshot);

    /**
     * 记录与已持久化快照关联的发布事件。
     * 该调用是显式的，因此保存快照不会隐含生命周期、审计或 Outbox 副作用，可由应用
     * 事务的拥有者组合使用。
     */
    void recordSnapshotPublication(CatalogSnapshot snapshot, String eventType);

    /**
     * 列出给定环境的快照摘要，按版本降序排列（最新在前）。
     *
     * <p>供管理控制台展示快照历史。返回的摘要不包含完整快照内容。</p>
     *
     * @param environment 目标环境（如 "production"）
     * @param limit 返回摘要的最大数量
     * @return 快照摘要；永不为 {@code null}
     */
    List<SnapshotSummary> listSnapshots(String environment, int limit);
}
