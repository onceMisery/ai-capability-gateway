package com.ai.gateway.adapter.web.controller;

import com.ai.gateway.application.console.ConfigQueryUseCase;
import com.ai.gateway.domain.model.AdminAction;
import com.ai.gateway.domain.model.CacheStatus;
import com.ai.gateway.domain.model.GatewayConfig;
import com.ai.gateway.domain.model.RateLimitRule;
import com.ai.gateway.domain.port.RateLimitAdminPort;
import com.ai.gateway.adapter.web.support.ApiResponse;
import com.ai.gateway.adapter.web.support.SecurityHelper;
import com.ai.gateway.adapter.web.GatewayWebProperties;
import com.ai.gateway.domain.port.AuthenticationPort;
import com.ai.gateway.domain.port.AuthorizationPort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import lombok.extern.slf4j.Slf4j;

/**
 * 从管理后台查询网关非敏感运行时配置的 REST 控制器。
 *
 * <p>在 {@code /admin/v1} 下暴露只读端点：</p>
 * <ul>
 * <li>网关非敏感配置（GET /admin/v1/config）。</li>
 * <li>缓存子系统状态（GET /admin/v1/cache/status）。</li>
 * <li>Sentinel 限流规则（GET/POST/DELETE /admin/v1/ratelimit/rules）。</li>
 * </ul>
 *
 * @author cmiracle@163.com
 * @since 0.1.0
 */
@RestController
@RequestMapping("/admin/v1")
@Slf4j
public class SystemConfigController {

    private final ConfigQueryUseCase configQueryUseCase;
    private final RateLimitAdminPort rateLimitAdminPort;
    private final AuthenticationPort authenticationPort;
    private final AuthorizationPort authorizationPort;
    private final String ratelimitProvider;

    /** 受保护资源：阈值可修改，但规则不可删除。 */
    private static final Set<String> PROTECTED_RESOURCES = Set.of(
            "gateway:global", "gateway:llm:routing"
    );

    /**
     * 构造新的 SystemConfigController。
     *
     * @param configQueryUseCase 配置查询用例
     */
    public SystemConfigController(ConfigQueryUseCase configQueryUseCase,
                                   RateLimitAdminPort rateLimitAdminPort,
                                   AuthenticationPort authenticationPort,
                                   AuthorizationPort authorizationPort,
                                   GatewayWebProperties properties) {
        this.configQueryUseCase = Objects.requireNonNull(configQueryUseCase);
        this.rateLimitAdminPort = Objects.requireNonNull(rateLimitAdminPort);
        this.authenticationPort = Objects.requireNonNull(authenticationPort);
        this.authorizationPort = Objects.requireNonNull(authorizationPort);
        this.ratelimitProvider = Objects.requireNonNull(properties).getRatelimit().getProvider();
    }

    /**
     * GET /admin/v1/config
     *
     * <p>返回网关的非敏感运行时配置。</p>
     */
    @GetMapping("/config")
    public ResponseEntity<GatewayConfig> getConfig() {
        return ResponseEntity.ok(configQueryUseCase.getGatewayConfig());
    }

    /**
     * GET /admin/v1/cache/status
     *
     * <p>返回缓存子系统状态。</p>
     */
    @GetMapping("/cache/status")
    public ResponseEntity<CacheStatus> getCacheStatus() {
        return ResponseEntity.ok(configQueryUseCase.getCacheStatus());
    }

    /**
     * GET /admin/v1/ratelimit/rules
     *
     * <p>返回当前的 Sentinel 限流规则。</p>
     */
    @GetMapping("/ratelimit/rules")
    public ResponseEntity<List<RateLimitRule>> getRateLimitRules() {
        return ResponseEntity.ok(configQueryUseCase.getRateLimitRules());
    }

    /**
     * POST /admin/v1/ratelimit/rules
     *
     * <p>创建或更新一条限流规则。stub 模式下返回 409。</p>
     */
    @PostMapping("/ratelimit/rules")
    public ResponseEntity<Map<String, Object>> createRateLimitRule(@RequestBody RateLimitRule rule) {
        if ("stub".equals(ratelimitProvider)) {
            return ApiResponse.error("RATELIMIT_DISABLED",
                    "Rate-limit management is not available in stub mode",
                    org.springframework.http.HttpStatus.CONFLICT);
        }

        SecurityHelper.requireAdmin(authenticationPort, authorizationPort, AdminAction.CONFIGURE);

        rateLimitAdminPort.saveRule(rule);
        log.info("Rate-limit rule saved: type={}, resource={}", rule.type(), rule.resource());
        return ApiResponse.ok(Map.of("message", "Rate-limit rule saved"));
    }

    /**
     * DELETE /admin/v1/ratelimit/rules/{type}/{resource}
     *
     * <p>删除一条限流规则。受保护资源（gateway:global、gateway:llm:routing）
     * 不可删除——仅可修改其阈值。</p>
     */
    @DeleteMapping("/ratelimit/rules/{type}/{resource:.+}")
    public ResponseEntity<Map<String, Object>> deleteRateLimitRule(
            @PathVariable String type,
            @PathVariable String resource) {
        if ("stub".equals(ratelimitProvider)) {
            return ApiResponse.error("RATELIMIT_DISABLED",
                    "Rate-limit management is not available in stub mode",
                    org.springframework.http.HttpStatus.CONFLICT);
        }

        SecurityHelper.requireAdmin(authenticationPort, authorizationPort, AdminAction.CONFIGURE);

        if (PROTECTED_RESOURCES.contains(resource)) {
            return ApiResponse.error("PROTECTED_RESOURCE",
                    "Resource '" + resource + "' is protected and cannot be deleted. " +
                    "Its threshold may be modified via POST.",
                    org.springframework.http.HttpStatus.CONFLICT);
        }

        rateLimitAdminPort.deleteRule(type, resource);
        log.info("Rate-limit rule deleted: type={}, resource={}", type, resource);
        return ApiResponse.ok(Map.of("message", "Rate-limit rule deleted"));
    }
}
