package com.ai.gateway.adapter.web.controller;

import com.ai.gateway.application.console.ConsoleAuthUseCase;
import com.ai.gateway.adapter.web.support.SecurityHelper;
import com.ai.gateway.domain.model.Principal;
import com.ai.gateway.domain.port.AuthenticationPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Objects;

/**
 * 管理后台认证控制器
 *
 * <p>提供登录、登出、whoami、认证模式探测端点，支持 stub 和 sa-token 两种模式。</p>
 *
 * @since 0.1.0
 */
@RestController
@RequestMapping("/admin/v1/console/auth")
public class ConsoleAuthController {

    private static final Logger log = LoggerFactory.getLogger(ConsoleAuthController.class);

    private final ConsoleAuthUseCase consoleAuthUseCase;
    private final AuthenticationPort authenticationPort;

    /**
     * 构造 ConsoleAuthController
     */
    public ConsoleAuthController(ConsoleAuthUseCase consoleAuthUseCase,
                                  AuthenticationPort authenticationPort) {
        this.consoleAuthUseCase = Objects.requireNonNull(consoleAuthUseCase);
        this.authenticationPort = Objects.requireNonNull(authenticationPort);
    }

    /**
     * GET /admin/v1/console/auth/capabilities — 认证模式探测（匿名可访问）
     */
    @GetMapping("/capabilities")
    public ResponseEntity<Map<String, Object>> getCapabilities() {
        return ResponseEntity.ok(consoleAuthUseCase.getCapabilities());
    }

    /**
     * POST /admin/v1/console/auth/login — 登录
     */
    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");

        if (username == null || username.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "ERROR",
                    "error", Map.of("errorCode", "VALIDATION_FAILED", "message", "username is required")
            ));
        }
        if (password == null || password.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "ERROR",
                    "error", Map.of("errorCode", "VALIDATION_FAILED", "message", "password is required")
            ));
        }

        try {
            return ResponseEntity.ok(consoleAuthUseCase.login(username, password));
        } catch (Exception e) {
            log.error("Login failed for user: {}", username, e);
            return ResponseEntity.status(401).body(Map.of(
                    "status", "ERROR",
                    "error", Map.of("errorCode", "AUTHENTICATION_FAILED", "message", "authentication failed")
            ));
        }
    }

    /**
     * POST /admin/v1/console/auth/logout — 登出
     */
    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout(
            @org.springframework.web.bind.annotation.RequestHeader("Authorization") String authorization) {
        Principal principal = extractPrincipalFromRequest();
        if (principal != null) {
            consoleAuthUseCase.logout(principal, extractBearer(authorization));
        }
        return ResponseEntity.ok(Map.of(
                "status", "OK",
                "data", Map.of("message", "Logged out successfully")
        ));
    }

    @PostMapping("/refresh")
    public ResponseEntity<Map<String, Object>> refresh(@RequestBody Map<String, String> body) {
        String refreshToken = body.get("refreshToken");
        if (refreshToken == null || refreshToken.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "ERROR",
                    "error", Map.of("errorCode", "VALIDATION_FAILED",
                            "message", "refreshToken is required")));
        }
        try {
            return ResponseEntity.ok(consoleAuthUseCase.refresh(refreshToken));
        } catch (SecurityException e) {
            return ResponseEntity.status(401).body(Map.of(
                    "status", "ERROR",
                    "error", Map.of("errorCode", "AUTHENTICATION_FAILED",
                            "message", "invalid or expired refresh token")));
        }
    }

    private String extractBearer(String authorization) {
        return authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring("Bearer ".length()).trim() : authorization;
    }

    /**
     * GET /admin/v1/console/auth/whoami — 当前登录用户信息
     */
    @GetMapping("/whoami")
    public ResponseEntity<Map<String, Object>> whoami() {
        try {
            // 从请求中提取 Principal
            Principal principal = extractPrincipalFromRequest();
            if (principal == null) {
                return ResponseEntity.status(401).body(Map.of(
                        "status", "ERROR",
                        "error", Map.of("errorCode", "AUTHENTICATION_FAILED", "message", "no valid token")
                ));
            }
            return ResponseEntity.ok(consoleAuthUseCase.me(principal));
        } catch (Exception e) {
            log.warn("Whoami failed", e);
            return ResponseEntity.status(401).body(Map.of(
                    "status", "ERROR",
                    "error", Map.of("errorCode", "AUTHENTICATION_FAILED", "message", "invalid token")
            ));
        }
    }

    /**
     * 从当前请求的 Authorization header 提取并验证 Principal
     */
    private Principal extractPrincipalFromRequest() {
        return SecurityHelper.getCurrentPrincipal(authenticationPort);
    }
}
