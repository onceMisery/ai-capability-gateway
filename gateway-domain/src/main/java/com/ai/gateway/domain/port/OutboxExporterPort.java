package com.ai.gateway.domain.port;

/**
 * 将事务性 Outbox 事件导出到外部持久化接收端。
 * 实现必须在下游系统已接受事件后才返回；抛出异常则事件保持待重试状态。
 */
public interface OutboxExporterPort {

    void export(OutboxPort.OutboxEvent event) throws Exception;
}
