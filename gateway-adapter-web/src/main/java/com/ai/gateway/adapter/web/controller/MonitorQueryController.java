package com.ai.gateway.adapter.web.controller;

import com.ai.gateway.application.console.AuditQueryUseCase;
import com.ai.gateway.application.console.StatsQueryUseCase;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import lombok.extern.slf4j.Slf4j;

/**
 * 从管理后台进行监控与审计查询的 REST 控制器。
 *
 * <p>在 {@code /admin/v1} 下暴露端点：</p>
 * <ul>
 * <li>带过滤与分页的审计事件查询。</li>
 * <li>按结果码划分的时间序列统计。</li>
 * <li>按能力聚合的统计。</li>
 * </ul>
 *
 * @author cmiracle@163.com
 * @since 0.1.0
 */
@RestController
@RequestMapping("/admin/v1")
@Slf4j
public class MonitorQueryController {

    private final AuditQueryUseCase auditQueryUseCase;
    private final StatsQueryUseCase statsQueryUseCase;

    /**
     * 构造新的 MonitorQueryController。
     *
     * @param auditQueryUseCase 审计查询用例
     * @param statsQueryUseCase 统计查询用例
     */
    public MonitorQueryController(AuditQueryUseCase auditQueryUseCase,
                                   StatsQueryUseCase statsQueryUseCase) {
        this.auditQueryUseCase = Objects.requireNonNull(auditQueryUseCase);
        this.statsQueryUseCase = Objects.requireNonNull(statsQueryUseCase);
    }

    /**
     * GET /admin/v1/audits
     *
     * <p>带过滤与分页地查询审计事件。</p>
     */
    @GetMapping("/audits")
    public ResponseEntity<Map<String, Object>> queryAudits(
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String capabilityId,
            @RequestParam(required = false) String requestId,
            @RequestParam(required = false) String resultCode,
            @RequestParam(required = false) Long from,
            @RequestParam(required = false) Long to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Integer limit) {
        // 'limit' is an alias for 'size' (backward compatibility)
        int effectiveSize = (limit != null && limit > 0) ? limit : size;
        Map<String, Object> result = auditQueryUseCase.queryAuditEvents(
                eventType, capabilityId, requestId, resultCode, from, to, page, effectiveSize);
        return ResponseEntity.ok(result);
    }

    /**
     * GET /admin/v1/stats/time-series
     *
     * <p>返回按结果码分组的时间序列数据。</p>
     */
    @GetMapping("/stats/time-series")
    public ResponseEntity<List<Map<String, Object>>> timeSeriesStats(
            @RequestParam long from,
            @RequestParam long to) {
        List<Map<String, Object>> result = statsQueryUseCase.timeSeriesByResultCode(from, to);
        return ResponseEntity.ok(result);
    }

    /**
     * GET /admin/v1/stats/capabilities
     *
     * <p>返回按能力聚合的统计。</p>
     */
    @GetMapping("/stats/capabilities")
    public ResponseEntity<List<Map<String, Object>>> capabilityStats(
            @RequestParam long from,
            @RequestParam long to) {
        List<Map<String, Object>> result = statsQueryUseCase.capabilityStats(from, to);
        return ResponseEntity.ok(result);
    }
}
