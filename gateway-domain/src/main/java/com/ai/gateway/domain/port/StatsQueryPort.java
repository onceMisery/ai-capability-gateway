package com.ai.gateway.domain.port;

import java.util.List;
import java.util.Map;

/**
 * 从审计存储查询聚合统计数据的端口。
 *
 * <p>提供时间序列与能力级别的聚合数据，供管理控制台的监控仪表盘使用。适配器通常查询
 * PostgreSQL 的聚合函数或专门的物化视图。</p>
 *
 * @since 0.1.0
 */
public interface StatsQueryPort {

    /**
     * 返回在指定时间范围内按结果码分组的审计事件时间序列计数。
     *
     * <p>返回列表中的每个条目代表一个时间桶，含结果码及其计数。桶大小由适配器依据
     * 时间范围决定。</p>
     *
     * @param fromEpochMs 时间范围起点（含，epoch 毫秒）
     * @param toEpochMs 时间范围终点（不含，epoch 毫秒）
     * @return 时间序列数据点；永不为 {@code null}
     */
    List<Map<String, Object>> timeSeriesByResultCode(long fromEpochMs, long toEpochMs);

    /**
     * 返回在指定时间范围内每个能力的成功/失败聚合计数。
     *
     * <p>返回列表中的每个条目包含能力 ID、成功计数、失败计数与平均耗时。</p>
     *
     * @param fromEpochMs 时间范围起点（含，epoch 毫秒）
     * @param toEpochMs 时间范围终点（不含，epoch 毫秒）
     * @return 按能力维度的统计数据；永不为 {@code null}
     */
    List<Map<String, Object>> capabilityStats(long fromEpochMs, long toEpochMs);
}
