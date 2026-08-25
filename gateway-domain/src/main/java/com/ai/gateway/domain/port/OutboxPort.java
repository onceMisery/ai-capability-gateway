package com.ai.gateway.domain.port;

import java.time.Instant;
import java.util.List;

/**
 * 实现事务性 Outbox 模式的端口。
 *
 * <p>（一致性）规定发布、操作状态变更与审计索引写入都在本地事务中提交。事务性 Outbox
 * 用于将事件导出到消息系统或 SIEM，避免"状态提交成功但审计通知丢失"的场景。</p>
 *
 * <p>{@code operation_record} 表使用版本号与条件更新来防止重复执行。活动快照不可变；
 * 只能创建新快照。实例内索引必须记录其 snapshotVersion 并在健康检查时暴露。</p>
 *
 * <p>（依赖失败策略）：若审计导出不可用，本地 Outbox 累积并告警；事件绝不得丢弃。终态
 * 写入不得为性能而降级为有损的异步操作。</p>
 *
 * <p>实现此端口的适配器管理 PostgreSQL 中的 Outbox 表并提供导出工作线程。该端口是纯粹的
 * 领域抽象，不依赖任何框架。</p>
 *
 * @see OutboxEvent
 * @since 0.1.0
 */
public interface OutboxPort {

    /**
     * 发布一个事件到 Outbox 以供异步导出。
     *
     * <p>规定：事件与状态变更写在同一个本地事务中，确保至少一次投递到下游消息系统或 SIEM。
     * 载荷是受控 JSON 字符串，不得包含堆栈、内部地址或敏感参数。</p>
     *
     * @param eventType 事件类型（如 "REQUEST_ACCEPTED"、"STARTED"、"SUCCEEDED"）
     * @param payloadJson 受控 JSON 载荷
     */
    void publish(String eventType, String payloadJson);

    /**
     * 轮询未导出的 Outbox 事件，供导出工作线程处理。
     *
     * <p>规定：导出工作线程轮询尚未标记为已导出的事件。若下游系统不可用，事件在本地累积
     * 并告警；事件永不丢弃。</p>
     *
     * @param batchSize 轮询的最大事件数
     * @return 未导出事件列表，按创建时间排序；永不为 {@code null}
     */
    List<OutboxEvent> pollUnexported(int batchSize);

    /**
     * 将一个 Outbox 事件标记为成功导出。
     *
     * <p>规定：下游系统确认接收后，事件被标记为已导出以防重复处理。下游侧的幂等消费者
     * 负责处理至少一次投递语义。</p>
     *
     * @param eventId 待标记为已导出的 Outbox 事件 ID
     */
    void markExported(long eventId);

    /**
     * 等待或已完成导出的 Outbox 事件。
     *
     * <p>规定：每个事件代表一次状态变更、审计记录或通知，必须通过事务性 Outbox 模式导出
     * 到下游消息系统或 SIEM。</p>
     *
     * @param id 唯一事件标识
     * @param eventType 事件类型
     * @param payloadJson 受控 JSON 载荷
     * @param createdAt 事件创建时间戳
     */
    record OutboxEvent(long id, String eventType, String payloadJson, Instant createdAt) {
    }
}
