package com.ai.gateway.adapter.auth.satoken;

import com.ai.gateway.domain.model.AdminAction;
import com.ai.gateway.domain.model.AclPolicyStatus;
import com.ai.gateway.domain.model.CapabilityAclEntry;
import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.Principal;
import com.ai.gateway.domain.port.AclRepository;
import com.ai.gateway.domain.port.AuthorizationPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * {@link AuthorizationPort} 的 Sa-Token 参考实现，强制实施能力级别
 * （capabilityId + version）的授权。
 *
 * <p>核心决策是将调用方的角色与"能力 → 允许角色"的访问控制列表（ACL）进行匹配。
 * 授权以能力 ID 和版本为键。生产环境的装配通过 {@link AclRepository} 从 PostgreSQL
 * 加载 ACL 条目；该 Map 仅是进程内缓存。</p>
 *
 * <p>空 ACL 的行为是明确的。生产环境的装配采用默认拒绝；仅用于开发环境的调用方
 * 可以通过布尔构造参数选择全部放行。ACL 加载失败时始终拒绝。</p>
 *
 * @author cmiracle@163.com
 * @since 0.1.0
 */
public class SaTokenAuthorizationAdapter implements AuthorizationPort {

    private static final Logger log = LoggerFactory.getLogger(SaTokenAuthorizationAdapter.class);

    /**
     * 授予所有管理操作的角色。
     */
    public static final String ROLE_ADMIN = "admin";

    /**
     * 授予所有能力执行的权限通配符。
     */
    public static final String PERMISSION_WILDCARD = "*";

    private final boolean allowAllIfAclEmpty;
    private final AclRepository aclRepository;

    /**
     * 能力 ACL：capabilityId → 允许执行该能力的角色集合。
     */
    private volatile Map<CapabilityKey, AclPolicy> capabilityAcl = Map.of();
    private volatile boolean aclLoadHealthy = true;

    /**
     * 以安全的默认策略构造适配器（ACL 为空时拒绝）。
     */
    public SaTokenAuthorizationAdapter() {
        this(false);
    }

    /**
     * 以明确的空 ACL 策略构造适配器。
     *
     * @param allowAllIfAclEmpty 为 {@code true} 时，空 ACL 将授权所有已认证的调用方；
     * 为 {@code false} 时，空 ACL 拒绝所有请求（严格的默认拒绝）
     */
    public SaTokenAuthorizationAdapter(boolean allowAllIfAclEmpty) {
        this(allowAllIfAclEmpty, null);
    }

    /**
     * 使用 AclRepository 构造适配器，用于从 PostgreSQL 加载 ACL 条目。
     *
     * @param allowAllIfAclEmpty 空 ACL 策略
     * @param aclRepository ACL 仓库；在桩（stub）模式下可以为 {@code null}
     */
    public SaTokenAuthorizationAdapter(boolean allowAllIfAclEmpty, AclRepository aclRepository) {
        this.allowAllIfAclEmpty = allowAllIfAclEmpty;
        this.aclRepository = aclRepository;
        loadAcl();
    }

    @Override
    public List<CapabilityManifest> filterVisibleCapabilities(
            Principal principal, List<CapabilityManifest> candidates) {
        Objects.requireNonNull(principal, "principal must not be null");
        Objects.requireNonNull(candidates, "candidates must not be null");
        List<CapabilityManifest> visible = new ArrayList<>();
        for (CapabilityManifest manifest : candidates) {
            String capabilityId = manifest.metadata().id();
            String version = manifest.metadata().version();
            if (authorizeExecution(principal, capabilityId, version)) {
                visible.add(manifest);
            }
        }
        return visible;
    }

    @Override
    public boolean authorizeExecution(Principal principal, String capabilityId, String version) {
        Objects.requireNonNull(principal, "principal must not be null");
        Objects.requireNonNull(capabilityId, "capabilityId must not be null");

        if (!aclLoadHealthy) {
            return false;
        }
        // 通配符仅在 ACL 数据源成功加载之后才有意义。
        // 基础设施故障必须保持默认拒绝（fail-closed）。
        if (principal.permissions().contains(PERMISSION_WILDCARD)) {
            return true;
        }
        AclPolicy policy = capabilityAcl.get(new CapabilityKey(capabilityId, version));
        if (policy == null) {
            policy = capabilityAcl.get(new CapabilityKey(capabilityId, "*"));
        }
        if (policy == null) {
            boolean allowed = capabilityAcl.isEmpty() && allowAllIfAclEmpty;
            if (!allowed) {
                log.debug("Execution denied (no ACL entry): capability={}, version={}",
                        capabilityId, version);
            }
            return allowed;
        }
        boolean roleAllowed = policy.allowedRoles().isEmpty()
                || principal.roles().stream().anyMatch(policy.allowedRoles()::contains);
        boolean permissionsAllowed = principal.permissions().contains(PERMISSION_WILDCARD)
                || new HashSet<>(principal.permissions()).containsAll(policy.requiredPermissions());
        return roleAllowed && permissionsAllowed;
    }

    @Override
    public boolean authorizeAdmin(Principal principal, AdminAction action) {
        Objects.requireNonNull(principal, "principal must not be null");
        Objects.requireNonNull(action, "action must not be null");
        // 管理操作需要 admin 角色或通配符权限；否则默认拒绝。
        return principal.roles().contains(ROLE_ADMIN)
                || principal.permissions().contains(PERMISSION_WILDCARD);
    }

    @Override
    public AclPolicyStatus aclPolicyStatus() {
        return new AclPolicyStatus(
                aclLoadHealthy,
                capabilityAcl.size(),
                allowAllIfAclEmpty ? "ALLOW" : "DENY");
    }

    /**
     * 注册一条 ACL 条目，授予给定角色访问某能力的权限。
     *
     * @param capabilityId 能力标识
     * @param roles 允许执行该能力的角色
     */
    public synchronized void grant(String capabilityId, Set<String> roles) {
        Objects.requireNonNull(capabilityId, "capabilityId must not be null");
        Objects.requireNonNull(roles, "roles must not be null");
        Map<CapabilityKey, AclPolicy> updated = new HashMap<>(capabilityAcl);
        updated.put(new CapabilityKey(capabilityId, "*"),
                new AclPolicy(Set.copyOf(roles), Set.of()));
        capabilityAcl = Map.copyOf(updated);
    }

    public synchronized void grant(String capabilityId, String version, Set<String> roles,
                      Set<String> requiredPermissions) {
        Map<CapabilityKey, AclPolicy> updated = new HashMap<>(capabilityAcl);
        updated.put(new CapabilityKey(capabilityId, version),
                new AclPolicy(Set.copyOf(roles), Set.copyOf(requiredPermissions)));
        capabilityAcl = Map.copyOf(updated);
    }

    /**
     * 加载能力 ACL。
     *
     * <p>如果配置了 {@link AclRepository}，则从 PostgreSQL 加载条目。没有仓库时
     * ACL 保持为空，由明确的空 ACL 策略决定结果；加载失败时拒绝。</p>
     */
    protected synchronized void loadAcl() {
        if (aclRepository == null) {
            return;
        }
        try {
            List<CapabilityAclEntry> entries = aclRepository.findAllAclEntries();
            Map<CapabilityKey, AclPolicy> loaded = new HashMap<>();
            for (CapabilityAclEntry entry : entries) {
                loaded.put(
                        new CapabilityKey(entry.capabilityId(), entry.capabilityVersion()),
                        new AclPolicy(Set.copyOf(entry.allowedRoles()),
                                Set.copyOf(entry.requiredPermissions())));
            }
            capabilityAcl = Map.copyOf(loaded);
            aclLoadHealthy = true;
            log.info("Loaded {} ACL entries from database", entries.size());
        } catch (Exception e) {
            aclLoadHealthy = false;
            log.error("Failed to load ACL entries from database; authorization fails closed", e);
        }
    }

    /**
     * 从数据库刷新内存中的 ACL 缓存。
     *
     * <p>在管理控制台对 ACL 进行变更后调用，以确保授权决策反映最新的配置。</p>
     */
    public void refreshAcl() {
        loadAcl();
        log.info("ACL cache refreshed, {} entries loaded", capabilityAcl.size());
    }

    private record CapabilityKey(String capabilityId, String version) {
    }

    private record AclPolicy(Set<String> allowedRoles, Set<String> requiredPermissions) {
    }
}
