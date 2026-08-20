package com.ai.gateway.adapter.web.controller;

import com.ai.gateway.application.console.AclManageUseCase;
import com.ai.gateway.domain.model.AdminAction;
import com.ai.gateway.domain.model.AclPolicyStatus;
import com.ai.gateway.domain.model.CapabilityAclEntry;
import com.ai.gateway.domain.model.Permission;
import com.ai.gateway.domain.model.Role;
import com.ai.gateway.domain.port.AuthenticationPort;
import com.ai.gateway.domain.port.AuthorizationPort;
import com.ai.gateway.adapter.web.support.ApiResponse;
import com.ai.gateway.adapter.web.support.SecurityHelper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import lombok.extern.slf4j.Slf4j;

/**
 * 从管理后台管理能力 ACL 条目、角色与权限的 REST 控制器。
 *
 * <p>在 {@code /admin/v1} 下暴露 CRUD 端点：</p>
 * <ul>
 * <li>角色管理（GET/POST/PUT/DELETE /admin/v1/roles）。</li>
 * <li>权限管理（GET/POST/DELETE /admin/v1/permissions）。</li>
 * <li>能力 ACL 管理（GET/PUT /admin/v1/acl/capabilities）。</li>
 * </ul>
 *
 * @author cmiracle@163.com
 * @since 0.1.0
 */
@RestController
@RequestMapping("/admin/v1")
@Slf4j
public class AclAdminController {

    private final AclManageUseCase aclManageUseCase;
    private final AuthorizationPort authorizationPort;
    private final AuthenticationPort authenticationPort;

    /**
     * 构造新的 AclAdminController。
     *
     * @param aclManageUseCase ACL 管理用例
     */
    public AclAdminController(AclManageUseCase aclManageUseCase,
                               AuthorizationPort authorizationPort,
                               AuthenticationPort authenticationPort) {
        this.aclManageUseCase = Objects.requireNonNull(aclManageUseCase);
        this.authorizationPort = Objects.requireNonNull(authorizationPort);
        this.authenticationPort = Objects.requireNonNull(authenticationPort);
    }

    // ================================================================
    // ACL Entry endpoints
    // ================================================================

    /**
     * GET /admin/v1/acl/capabilities — 列出全部 ACL 条目
     */
    @GetMapping("/acl/capabilities")
    public ResponseEntity<List<CapabilityAclEntry>> listAclCapabilities() {
        return ResponseEntity.ok(aclManageUseCase.listAclEntries());
    }

    /**
     * GET /admin/v1/acl/capabilities/{capabilityId}/{version}
     */
    @GetMapping("/acl/capabilities/{capabilityId}/{version}")
    public ResponseEntity<CapabilityAclEntry> getAclEntry(
            @PathVariable String capabilityId,
            @PathVariable String version) {
        return aclManageUseCase.getAclEntry(capabilityId, version)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * PUT /admin/v1/acl/capabilities — capabilityId/version 位于请求体中
     */
    @PutMapping("/acl/capabilities")
    public ResponseEntity<Map<String, Object>> saveAclCapability(
            @RequestBody Map<String, Object> body) {
        SecurityHelper.requireAdmin(authenticationPort, authorizationPort, AdminAction.MANAGE_ACL);
        String capabilityId = (String) body.getOrDefault("capabilityId", "");
        String version = (String) body.getOrDefault("version", "");
        return doSaveAclEntry(capabilityId, version, body);
    }

    /**
     * GET /admin/v1/acl/policy — 获取当前 ACL 策略概览
     */
    @GetMapping("/acl/policy")
    public ResponseEntity<Map<String, Object>> getAclPolicy() {
        List<CapabilityAclEntry> entries = aclManageUseCase.listAclEntries();
        List<Role> roles = aclManageUseCase.listRoles();
        List<Permission> permissions = aclManageUseCase.listPermissions();
        AclPolicyStatus status = authorizationPort.aclPolicyStatus();
        return ResponseEntity.ok(Map.of(
                "aclLoadHealthy", status.loadHealthy(),
                "policyEpoch", authorizationPort.currentPolicyEpoch(),
                "aclEntryCount", status.loadedEntryCount(),
                "emptyAclDecision", status.emptyAclDecision(),
                "aclEntries", entries,
                "roles", roles,
                "permissions", permissions
        ));
    }

    private ResponseEntity<Map<String, Object>> doSaveAclEntry(
            String capabilityId, String version, Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> allowedRoles = (List<String>) body.getOrDefault("allowedRoles", List.of());
        String updatedBy = (String) body.getOrDefault("updatedBy", "system");
        aclManageUseCase.saveAclEntry(capabilityId, version, allowedRoles, updatedBy);
        // 刷新授权缓存
        authorizationPort.refreshAcl();
        return ApiResponse.ok(Map.of("message", "ACL entry saved"));
    }

    /**
     * DELETE /admin/v1/acl/capabilities/{capabilityId}/{version} — 删除指定 ACL 条目
     */
    @DeleteMapping("/acl/capabilities/{capabilityId}/{version}")
    public ResponseEntity<Map<String, Object>> deleteAclEntry(
            @PathVariable String capabilityId,
            @PathVariable String version) {
        SecurityHelper.requireAdmin(authenticationPort, authorizationPort, AdminAction.MANAGE_ACL);
        aclManageUseCase.deleteAclEntry(capabilityId, version);
        // 刷新授权缓存
        authorizationPort.refreshAcl();
        return ApiResponse.ok(Map.of("message", "ACL entry deleted"));
    }

    // ================================================================
    // Role endpoints
    // ================================================================

    /**
     * GET /admin/v1/roles — 列出全部角色
     */
    @GetMapping("/roles")
    public ResponseEntity<List<Role>> listRoles() {
        return ResponseEntity.ok(aclManageUseCase.listRoles());
    }

    /**
     * GET /admin/v1/roles/{name} — 获取指定角色
     */
    @GetMapping("/roles/{name}")
    public ResponseEntity<Role> getRole(@PathVariable String name) {
        return aclManageUseCase.getRole(name)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * POST /admin/v1/roles — 创建角色
     */
    @PostMapping("/roles")
    public ResponseEntity<Map<String, Object>> createRole(@RequestBody Map<String, Object> body) {
        SecurityHelper.requireAdmin(authenticationPort, authorizationPort, AdminAction.MANAGE_ACL);
        String name = (String) body.get("name");
        String description = (String) body.getOrDefault("description", "");
        @SuppressWarnings("unchecked")
        List<String> permissions = (List<String>) body.getOrDefault("permissions", List.of());
        aclManageUseCase.saveRole(name, description, permissions);
        authorizationPort.refreshAcl();
        return ResponseEntity.ok(Map.of("message", "Role saved"));
    }

    /**
     * PUT /admin/v1/roles/{name} — 更新角色
     */
    @PutMapping("/roles/{name}")
    public ResponseEntity<Map<String, Object>> updateRole(
            @PathVariable String name,
            @RequestBody Map<String, Object> body) {
        SecurityHelper.requireAdmin(authenticationPort, authorizationPort, AdminAction.MANAGE_ACL);
        String description = (String) body.getOrDefault("description", "");
        @SuppressWarnings("unchecked")
        List<String> permissions = (List<String>) body.getOrDefault("permissions", List.of());
        aclManageUseCase.saveRole(name, description, permissions);
        authorizationPort.refreshAcl();
        return ResponseEntity.ok(Map.of("message", "Role updated"));
    }

    /**
     * DELETE /admin/v1/roles/{name} — 删除角色
     */
    @DeleteMapping("/roles/{name}")
    public ResponseEntity<Map<String, Object>> deleteRole(@PathVariable String name) {
        SecurityHelper.requireAdmin(authenticationPort, authorizationPort, AdminAction.MANAGE_ACL);

        // 检查角色是否被 ACL 引用
        List<CapabilityAclEntry> entries = aclManageUseCase.listAclEntries();
        boolean referenced = entries.stream()
                .anyMatch(e -> e.allowedRoles().contains(name));
        if (referenced) {
            return ApiResponse.conflict("Role '" + name + "' is referenced by one or more ACL entries and cannot be deleted");
        }

        aclManageUseCase.deleteRole(name);
        authorizationPort.refreshAcl();
        return ApiResponse.ok(Map.of("message", "Role deleted"));
    }

    // ================================================================
    // Permission endpoints
    // ================================================================

    /**
     * GET /admin/v1/permissions — 列出全部权限
     */
    @GetMapping("/permissions")
    public ResponseEntity<List<Permission>> listPermissions() {
        return ResponseEntity.ok(aclManageUseCase.listPermissions());
    }

    /**
     * POST /admin/v1/permissions — 创建权限
     */
    @PostMapping("/permissions")
    public ResponseEntity<Map<String, Object>> createPermission(@RequestBody Map<String, Object> body) {
        SecurityHelper.requireAdmin(authenticationPort, authorizationPort, AdminAction.MANAGE_ACL);
        String name = (String) body.get("name");
        String description = (String) body.getOrDefault("description", "");
        aclManageUseCase.savePermission(name, description);
        authorizationPort.refreshAcl();
        return ResponseEntity.ok(Map.of("message", "Permission saved"));
    }

    /**
     * DELETE /admin/v1/permissions/{name} — 删除权限
     */
    @DeleteMapping("/permissions/{name}")
    public ResponseEntity<Map<String, Object>> deletePermission(@PathVariable String name) {
        SecurityHelper.requireAdmin(authenticationPort, authorizationPort, AdminAction.MANAGE_ACL);

        // 检查权限是否被角色引用
        List<Role> roles = aclManageUseCase.listRoles();
        boolean referenced = roles.stream()
                .anyMatch(r -> r.permissions().contains(name));
        if (referenced) {
            return ApiResponse.conflict("Permission '" + name + "' is assigned to one or more roles and cannot be deleted");
        }

        aclManageUseCase.deletePermission(name);
        authorizationPort.refreshAcl();
        return ApiResponse.ok(Map.of("message", "Permission deleted"));
    }
}
