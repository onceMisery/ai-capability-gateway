package com.ai.gateway.adapter.web.controller;

import com.ai.gateway.application.console.AuditQueryUseCase;
import com.ai.gateway.application.console.StatsQueryUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * REST controller for monitoring and audit queries from the admin console.
 *
 * <p>Exposes endpoints under {@code /admin/v1} for:</p>
 * <ul>
 * <li>Audit event queries with filtering and pagination.</li>
 * <li>Time-series statistics by result code.</li>
 * <li>Per-capability aggregated statistics.</li>
 * </ul>
 *
 * @since 0.1.0
 */
@RestController
@RequestMapping("/admin/v1")
public class MonitorQueryController {

    private static final Logger log = LoggerFactory.getLogger(MonitorQueryController.class);

    private final AuditQueryUseCase auditQueryUseCase;
    private final StatsQueryUseCase statsQueryUseCase;

    /**
     * Constructs a new MonitorQueryController.
     *
     * @param auditQueryUseCase the audit query use case
     * @param statsQueryUseCase the stats query use case
     */
    public MonitorQueryController(AuditQueryUseCase auditQueryUseCase,
                                   StatsQueryUseCase statsQueryUseCase) {
        this.auditQueryUseCase = Objects.requireNonNull(auditQueryUseCase);
        this.statsQueryUseCase = Objects.requireNonNull(statsQueryUseCase);
    }

    /**
     * GET /admin/v1/audits
     *
     * <p>Queries audit events with filtering and pagination.</p>
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
     * <p>Returns time-series data grouped by result code.</p>
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
     * <p>Returns per-capability aggregated statistics.</p>
     */
    @GetMapping("/stats/capabilities")
    public ResponseEntity<List<Map<String, Object>>> capabilityStats(
            @RequestParam long from,
            @RequestParam long to) {
        List<Map<String, Object>> result = statsQueryUseCase.capabilityStats(from, to);
        return ResponseEntity.ok(result);
    }
}
