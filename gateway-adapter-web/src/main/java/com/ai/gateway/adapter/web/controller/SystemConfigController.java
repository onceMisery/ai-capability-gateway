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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

/**
 * REST controller for querying the gateway's non-sensitive runtime
 * configuration from the admin console.
 *
 * <p>Exposes read-only endpoints under {@code /admin/v1} for:</p>
 * <ul>
 * <li>Gateway non-sensitive configuration (GET /admin/v1/config).</li>
 * <li>Cache subsystem status (GET /admin/v1/cache/status).</li>
 * <li>Sentinel rate-limit rules (GET/POST/DELETE /admin/v1/ratelimit/rules).</li>
 * </ul>
 *
 * @since 0.1.0
 */
@RestController
@RequestMapping("/admin/v1")
public class SystemConfigController {

    private static final Logger log = LoggerFactory.getLogger(SystemConfigController.class);

    private final ConfigQueryUseCase configQueryUseCase;
    private final RateLimitAdminPort rateLimitAdminPort;
    private final AuthenticationPort authenticationPort;
    private final AuthorizationPort authorizationPort;
    private final String ratelimitProvider;

    /** Protected resources: threshold may be modified but the rule cannot be deleted. */
    private static final Set<String> PROTECTED_RESOURCES = Set.of(
            "gateway:global", "gateway:llm:routing"
    );

    /**
     * Constructs a new SystemConfigController.
     *
     * @param configQueryUseCase the config query use case
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
     * <p>Returns the gateway's non-sensitive runtime configuration.</p>
     */
    @GetMapping("/config")
    public ResponseEntity<GatewayConfig> getConfig() {
        return ResponseEntity.ok(configQueryUseCase.getGatewayConfig());
    }

    /**
     * GET /admin/v1/cache/status
     *
     * <p>Returns the cache subsystem status.</p>
     */
    @GetMapping("/cache/status")
    public ResponseEntity<CacheStatus> getCacheStatus() {
        return ResponseEntity.ok(configQueryUseCase.getCacheStatus());
    }

    /**
     * GET /admin/v1/ratelimit/rules
     *
     * <p>Returns the current Sentinel rate-limit rules.</p>
     */
    @GetMapping("/ratelimit/rules")
    public ResponseEntity<List<RateLimitRule>> getRateLimitRules() {
        return ResponseEntity.ok(configQueryUseCase.getRateLimitRules());
    }

    /**
     * POST /admin/v1/ratelimit/rules
     *
     * <p>Creates or updates a rate-limit rule. In stub mode, returns 409.</p>
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
     * <p>Deletes a rate-limit rule. Protected resources (gateway:global,
     * gateway:llm:routing) cannot be deleted — only their thresholds may
     * be modified.</p>
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
