package com.ai.gateway.domain.port;

import com.ai.gateway.domain.model.AuditEvent;
import com.ai.gateway.domain.model.AuditQueryCriteria;

import java.util.List;

/**
 * 从只读审计存储查询审计事件的端口。
 *
 * <p>将只读查询关注点从写侧 {@link AuditPort} 中分离。适配器可查询同一个 PostgreSQL
 * 表或专门的只读副本。</p>
 *
 * <p>支持按事件类型、能力 ID、请求 ID、结果码与时间范围过滤，并支持分页。</p>
 *
 * @see AuditEvent
 * @see AuditQueryCriteria
 * @since 0.1.0
 */
public interface AuditQueryPort {

    /**
     * 查询匹配给定条件的审计事件。
     *
     * <p>结果按时间戳降序排列（最新在前）。分页通过 criteria 的 page 与 size 字段应用。</p>
     *
     * @param criteria 查询条件；永不为 {@code null}
     * @return 匹配的审计事件；永不为 {@code null}
     */
    List<AuditEvent> query(AuditQueryCriteria criteria);

    /**
     * 统计匹配给定条件的审计事件数量。
     *
     * <p>用于分页的总计数计算。</p>
     *
     * @param criteria 查询条件；永不为 {@code null}
     * @return 匹配的事件数量
     */
    long count(AuditQueryCriteria criteria);
}
