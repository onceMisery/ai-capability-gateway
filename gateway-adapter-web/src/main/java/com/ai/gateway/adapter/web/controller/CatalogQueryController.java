package com.ai.gateway.adapter.web.controller;

import com.ai.gateway.application.console.CapabilityQueryUseCase;
import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.CapabilitySummary;
import com.ai.gateway.domain.model.CatalogEnvironment;
import com.ai.gateway.domain.model.SnapshotSummary;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

import lombok.extern.slf4j.Slf4j;

/**
 * 从管理后台查询能力清单与目录快照的 REST 控制器。
 *
 * <p>在 {@code /admin/v1} 下暴露只读端点：</p>
 * <ul>
 * <li>能力清单列表与详情查询。</li>
 * <li>目录快照历史查询。</li>
 * </ul>
 *
 * @author cmiracle@163.com
 * @since 0.1.0
 */
@RestController
@RequestMapping("/admin/v1")
@Slf4j
public class CatalogQueryController {

    private final CapabilityQueryUseCase capabilityQueryUseCase;

    /**
     * 构造新的 CatalogQueryController。
     *
     * @param capabilityQueryUseCase 能力查询用例
     */
    public CatalogQueryController(CapabilityQueryUseCase capabilityQueryUseCase) {
        this.capabilityQueryUseCase = Objects.requireNonNull(capabilityQueryUseCase);
    }

    /**
     * GET /admin/v1/capabilities
     *
     * <p>以摘要形式返回全部能力清单。</p>
     */
    @GetMapping("/capabilities")
    public ResponseEntity<List<CapabilitySummary>> listCapabilities() {
        List<CapabilitySummary> capabilities = capabilityQueryUseCase.listCapabilities();
        return ResponseEntity.ok(capabilities);
    }

    /**
     * GET /admin/v1/capabilities/{id}/versions/{version}
     *
     * <p>返回指定能力的完整清单详情。</p>
     */
    @GetMapping("/capabilities/{id}/versions/{version}")
    public ResponseEntity<?> getCapabilityDetail(
            @PathVariable String id,
            @PathVariable String version) {
        CapabilityManifest manifest = capabilityQueryUseCase.getCapabilityDetail(id, version);
        if (manifest == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(manifest);
    }

    /** GET /admin/v1/releases — 返回唯一能力目录的快照摘要列表。 */
    @GetMapping("/releases")
    public ResponseEntity<List<SnapshotSummary>> listSnapshots(
            @RequestParam(defaultValue = "50") int limit) {
        List<SnapshotSummary> snapshots =
                capabilityQueryUseCase.listSnapshots(CatalogEnvironment.DEFAULT, limit);
        return ResponseEntity.ok(snapshots);
    }
}
