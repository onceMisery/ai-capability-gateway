package com.ai.gateway.adapter.web.controller;

import com.ai.gateway.application.runtime.HealthReadinessUseCase;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import lombok.extern.slf4j.Slf4j;

/**
 * 健康检查端点的 REST 控制器。
 *
 * <p>该控制器暴露两个健康检查端点：</p>
 * <ul>
 * <li>{@code GET /health/readiness} — 检查网关是否就绪，可接受请求。
 * 校验：数据库连接、已加载的活动快照、所需密钥可用、适配器初始化完成。</li>
 * <li>{@code GET /health/liveness} — 检查进程是否存活。这是轻量级检查，
 * 仅验证进程正在运行且能够响应 HTTP 请求。</li>
 * </ul>
 *
 * <p>就绪探针在所有检查通过时返回 HTTP 200，任一检查失败则返回 HTTP 503；
 * 存活探针只要进程能响应就始终返回 HTTP 200。</p>
 *
 * @author cmiracle@163.com
 * @since 0.1.0
 */
@RestController
@RequestMapping("/health")
@Slf4j
public class HealthController {

    private final HealthReadinessUseCase readinessUseCase;

    /**
     * 构造新的 HealthController。
     *
     * @param readinessUseCase 就绪检查用例
     * @throws NullPointerException 任意参数为 null 时抛出
     */
    public HealthController(HealthReadinessUseCase readinessUseCase) {
        this.readinessUseCase = Objects.requireNonNull(readinessUseCase,
                "readinessUseCase must not be null");
    }

    /**
     * 就绪探针。
     *
     * <p>检查以下条件：</p>
     * <ol>
     * <li>数据库状态 — 校验与清单仓库的连接性。</li>
     * <li>已加载活动快照 — 校验存在生产快照。</li>
     * <li>所需密钥 — 校验必要的配置已就绪（未来密钥检查的占位）。</li>
     * <li>适配器初始化 — 校验目录端口可用。</li>
     * </ol>
     *
     * @return 全部检查通过时返回 HTTP 200 及检查结果；否则返回 HTTP 503
     */
    @GetMapping("/readiness")
    public ResponseEntity<Map<String, Object>> readiness() {
        HealthReadinessUseCase.Result readiness = readinessUseCase.check();
        Map<String, Object> checks = new LinkedHashMap<>(readiness.checks());
        boolean allHealthy = readiness.ready();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", allHealthy ? "UP" : "DOWN");
        body.put("checks", checks);

        if (allHealthy) {
            return ResponseEntity.ok(body);
        } else {
            log.warn("Readiness check failed: {}", checks);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
        }
    }

    /**
     * 存活探针。
     *
     * <p>这是轻量级检查，仅验证进程存活且能响应 HTTP 请求，不检查下游依赖。</p>
     *
     * @return 只要进程能响应，始终返回 HTTP 200
     */
    @GetMapping("/liveness")
    public ResponseEntity<Map<String, Object>> liveness() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.ok(body);
    }

}
