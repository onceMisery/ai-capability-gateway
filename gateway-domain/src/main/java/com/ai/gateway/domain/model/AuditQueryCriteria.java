package com.ai.gateway.domain.model;

import java.time.Instant;

/**
 * 从只读审计端口查询审计事件的筛选条件。
 *
 * <p>所有字段均为可选过滤器；null 表示在该维度上不加过滤。分页从 1 开始计数。</p>
 *
 * @param eventType 可选的事件类型过滤
 * @param capabilityId 可选的能力标识过滤
 * @param requestId 可选的请求标识过滤
 * @param resultCode 可选的结果码过滤
 * @param from 可选的时间范围起点（含）
 * @param to 可选的时间范围终点（不含）
 * @param page 从 1 开始的页码（默认 1）
 * @param size 每页大小（默认 20，最大 100）
 * @since 0.1.0
 */
public record AuditQueryCriteria(
        String eventType,
        String capabilityId,
        String requestId,
        String resultCode,
        Instant from,
        Instant to,
        int page,
        int size
) {

    /**
     * 创建带分页默认值的筛选条件。
     */
    public AuditQueryCriteria {
        if (page < 1) page = 1;
        if (size < 1) size = 20;
        if (size > 100) size = 100;
    }

    /**
     * 创建使用默认分页（page=1, size=20）的筛选条件。
     */
    public static AuditQueryCriteria of(String eventType, String capabilityId,
                                         String requestId, String resultCode,
                                         Instant from, Instant to) {
        return new AuditQueryCriteria(eventType, capabilityId, requestId, resultCode, from, to, 1, 20);
    }
}
