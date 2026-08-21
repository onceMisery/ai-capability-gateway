package com.ai.gateway.adapter.auth.satoken;

import com.ai.gateway.domain.model.AdminAction;
import com.ai.gateway.domain.model.AclPolicyStatus;
import com.ai.gateway.domain.model.CapabilityAclEntry;
import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.CapabilityReference;
import com.ai.gateway.domain.model.CapabilityVisibility;
import com.ai.gateway.domain.model.Principal;
import com.ai.gateway.domain.model.PolicySnapshot;
import com.ai.gateway.domain.port.AclRepository;
import com.ai.gateway.domain.port.AuthorizationPort;
import com.ai.gateway.domain.port.TelemetryPort;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

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
@Slf4j
public class SaTokenAuthorizationAdapter implements AuthorizationPort {

    /**
     * 授予所有管理操作的角色。
     */
    public static final String ROLE_ADMIN = "admin";

    /**
     * 授予所有能力执行的权限通配符。
     */
    public static final String PERMISSION_WILDCARD = "*";
    private static final int DEFAULT_MAX_VISIBILITY_CACHE_ENTRIES = 10_000;
    private static final long DEFAULT_MAX_VISIBILITY_CACHE_BYTES = 64L * 1024L * 1024L;

    private final boolean allowAllIfAclEmpty;
    private final AclRepository aclRepository;
    private final int maxVisibilityCacheEntries;
    private final long maxVisibilityCacheBytes;
    private final TelemetryPort telemetry;

    /**
     * 能力 ACL：capabilityId → 允许执行该能力的角色集合。
     */
    private volatile PolicyState policyState = new PolicyState(Map.of(), true, 1L);
    private final ConcurrentMap<VisibilityCacheKey, CachedVisibility> visibilityCache =
            new ConcurrentHashMap<>();
    private final Queue<VisibilityCacheKey> visibilityInsertionOrder =
            new ConcurrentLinkedQueue<>();
    private final AtomicLong visibilityCacheEvictions = new AtomicLong();
    private final AtomicLong visibilityCacheBytes = new AtomicLong();

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
        this(allowAllIfAclEmpty, aclRepository,
                DEFAULT_MAX_VISIBILITY_CACHE_ENTRIES, null);
    }

    public SaTokenAuthorizationAdapter(
            boolean allowAllIfAclEmpty,
            AclRepository aclRepository,
            int maxVisibilityCacheEntries,
            TelemetryPort telemetry) {
        this(allowAllIfAclEmpty, aclRepository, maxVisibilityCacheEntries,
                DEFAULT_MAX_VISIBILITY_CACHE_BYTES, telemetry);
    }

    public SaTokenAuthorizationAdapter(
            boolean allowAllIfAclEmpty,
            AclRepository aclRepository,
            int maxVisibilityCacheEntries,
            long maxVisibilityCacheBytes,
            TelemetryPort telemetry) {
        if (maxVisibilityCacheEntries <= 0) {
            throw new IllegalArgumentException("maxVisibilityCacheEntries must be positive");
        }
        if (maxVisibilityCacheBytes <= 0) {
            throw new IllegalArgumentException("maxVisibilityCacheBytes must be positive");
        }
        this.allowAllIfAclEmpty = allowAllIfAclEmpty;
        this.aclRepository = aclRepository;
        this.maxVisibilityCacheEntries = maxVisibilityCacheEntries;
        this.maxVisibilityCacheBytes = maxVisibilityCacheBytes;
        this.telemetry = telemetry;
        recordValue("gateway.authorization.visibility_cache.capacity",
                maxVisibilityCacheEntries);
        recordValue("gateway.authorization.visibility_cache.bytes.capacity",
                maxVisibilityCacheBytes);
        recordCacheSize();
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
    public PolicySnapshot resolvePolicySnapshot(Principal principal) {
        Objects.requireNonNull(principal, "principal must not be null");
        PolicyState state = policyState;
        CapabilityVisibility visibility = resolveVisibility(principal, state);
        return new PolicySnapshot(state.epoch(), state.loadHealthy(), visibility);
    }

    @Override
    public CapabilityVisibility resolveVisibility(Principal principal) {
        Objects.requireNonNull(principal, "principal must not be null");
        return resolveVisibility(principal, policyState);
    }

    private CapabilityVisibility resolveVisibility(Principal principal, PolicyState state) {
        if (!state.loadHealthy()) {
            return CapabilityVisibility.unavailable(state.epoch());
        }
        if (principal.permissions().contains(PERMISSION_WILDCARD)
                || (state.capabilityAcl().isEmpty() && allowAllIfAclEmpty)) {
            return CapabilityVisibility.all(state.epoch());
        }

        VisibilityCacheKey cacheKey = VisibilityCacheKey.from(principal, state.epoch());
        CachedVisibility cached = visibilityCache.get(cacheKey);
        if (cached != null) {
            incrementCache("hit");
            return cached.visibility();
        }
        incrementCache("miss");

        Set<CapabilityReference> visible = new HashSet<>();
        state.capabilityAcl().forEach((key, policy) -> {
            if (isAllowed(principal, policy)) {
                visible.add(new CapabilityReference(key.capabilityId(), key.version()));
            }
        });
        CapabilityVisibility resolved = CapabilityVisibility.restricted(state.epoch(), visible);
        CachedVisibility candidate = new CachedVisibility(
                resolved, estimatedWeight(cacheKey, resolved));
        CachedVisibility existing = visibilityCache.putIfAbsent(cacheKey, candidate);
        if (existing != null) {
            return existing.visibility();
        }
        visibilityCacheBytes.addAndGet(candidate.weightBytes());
        visibilityInsertionOrder.add(cacheKey);
        evictOverflow();
        recordCacheSize();
        return resolved;
    }

    @Override
    public long currentPolicyEpoch() {
        return policyState.epoch();
    }

    @Override
    public boolean authorizeExecution(Principal principal, String capabilityId, String version) {
        Objects.requireNonNull(principal, "principal must not be null");
        Objects.requireNonNull(capabilityId, "capabilityId must not be null");

        PolicyState state = policyState;
        if (!state.loadHealthy()) {
            return false;
        }
        // 通配符仅在 ACL 数据源成功加载之后才有意义。
        // 基础设施故障必须保持默认拒绝（fail-closed）。
        if (principal.permissions().contains(PERMISSION_WILDCARD)) {
            return true;
        }
        AclPolicy policy = state.capabilityAcl().get(new CapabilityKey(capabilityId, version));
        if (policy == null) {
            policy = state.capabilityAcl().get(new CapabilityKey(capabilityId, "*"));
        }
        if (policy == null) {
            boolean allowed = state.capabilityAcl().isEmpty() && allowAllIfAclEmpty;
            if (!allowed) {
                log.debug("Execution denied (no ACL entry): capability={}, version={}",
                        capabilityId, version);
            }
            return allowed;
        }
        return isAllowed(principal, policy);
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
                policyState.loadHealthy(),
                policyState.capabilityAcl().size(),
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
        PolicyState state = policyState;
        Map<CapabilityKey, AclPolicy> updated = new HashMap<>(state.capabilityAcl());
        updated.put(new CapabilityKey(capabilityId, "*"),
                new AclPolicy(Set.copyOf(roles), Set.of()));
        policyState = new PolicyState(Map.copyOf(updated), true, state.epoch() + 1);
        clearVisibilityCache();
    }

    public synchronized void grant(String capabilityId, String version, Set<String> roles,
                      Set<String> requiredPermissions) {
        PolicyState state = policyState;
        Map<CapabilityKey, AclPolicy> updated = new HashMap<>(state.capabilityAcl());
        updated.put(new CapabilityKey(capabilityId, version),
                new AclPolicy(Set.copyOf(roles), Set.copyOf(requiredPermissions)));
        policyState = new PolicyState(Map.copyOf(updated), true, state.epoch() + 1);
        clearVisibilityCache();
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
            long loadedEpoch = Math.max(1L, aclRepository.currentPolicyEpoch());
            policyState = new PolicyState(Map.copyOf(loaded), true, loadedEpoch);
            clearVisibilityCache();
            log.info("Loaded {} ACL entries from database at policy epoch {}",
                    entries.size(), loadedEpoch);
        } catch (Exception e) {
            PolicyState state = policyState;
            policyState = new PolicyState(state.capabilityAcl(), false, state.epoch());
            clearVisibilityCache();
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
        log.info("ACL cache refreshed, {} entries loaded at policy epoch {}",
                policyState.capabilityAcl().size(), policyState.epoch());
    }

    int visibilityCacheSize() {
        return visibilityCache.size();
    }

    long visibilityCacheEvictionCount() {
        return visibilityCacheEvictions.get();
    }

    long visibilityCacheBytes() {
        return visibilityCacheBytes.get();
    }

    private void evictOverflow() {
        while (visibilityCache.size() > maxVisibilityCacheEntries
                || visibilityCacheBytes.get() > maxVisibilityCacheBytes) {
            VisibilityCacheKey oldest = visibilityInsertionOrder.poll();
            if (oldest == null) {
                return;
            }
            CachedVisibility removed = visibilityCache.remove(oldest);
            if (removed != null) {
                visibilityCacheBytes.addAndGet(-removed.weightBytes());
                visibilityCacheEvictions.incrementAndGet();
                incrementCache("evicted");
            }
        }
    }

    private void clearVisibilityCache() {
        visibilityCache.clear();
        visibilityInsertionOrder.clear();
        visibilityCacheBytes.set(0L);
        recordCacheSize();
    }

    private void incrementCache(String outcome) {
        if (telemetry != null) {
            telemetry.increment("gateway.authorization.visibility_cache",
                    Map.of("outcome", outcome));
        }
    }

    private void recordCacheSize() {
        recordValue("gateway.authorization.visibility_cache.entries",
                visibilityCache.size());
        recordValue("gateway.authorization.visibility_cache.bytes",
                visibilityCacheBytes.get());
    }

    private void recordValue(String metric, long value) {
        if (telemetry != null) {
            telemetry.recordValue(metric, value,
                    Map.of("resource", "principal_visibility"));
        }
    }

    private static boolean isAllowed(Principal principal, AclPolicy policy) {
        boolean roleAllowed = policy.allowedRoles().isEmpty()
                || principal.roles().stream().anyMatch(policy.allowedRoles()::contains);
        boolean permissionsAllowed = principal.permissions().contains(PERMISSION_WILDCARD)
                || new HashSet<>(principal.permissions()).containsAll(policy.requiredPermissions());
        return roleAllowed && permissionsAllowed;
    }

    private static long estimatedWeight(
            VisibilityCacheKey key, CapabilityVisibility visibility) {
        long bytes = 96L + utf8Length(key.subject());
        for (String role : key.roles()) {
            bytes += 24L + utf8Length(role);
        }
        for (String permission : key.permissions()) {
            bytes += 24L + utf8Length(permission);
        }
        for (CapabilityReference reference : visibility.visibleCapabilities()) {
            bytes += 48L + utf8Length(reference.capabilityId())
                    + utf8Length(reference.version());
        }
        return Math.max(1L, bytes);
    }

    private static int utf8Length(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }

    private record CapabilityKey(String capabilityId, String version) {
    }

    private record AclPolicy(Set<String> allowedRoles, Set<String> requiredPermissions) {
    }

    private record CachedVisibility(
            CapabilityVisibility visibility, long weightBytes) {
    }

    private record PolicyState(
            Map<CapabilityKey, AclPolicy> capabilityAcl,
            boolean loadHealthy,
            long epoch) {
    }

    private record VisibilityCacheKey(
            String subject,
            long orgId,
            List<String> roles,
            List<String> permissions,
            long epoch) {

        private static VisibilityCacheKey from(Principal principal, long epoch) {
            List<String> roles = principal.roles().stream().sorted().toList();
            List<String> permissions = principal.permissions().stream().sorted().toList();
            return new VisibilityCacheKey(
                    principal.subject(), principal.orgId(), roles, permissions, epoch);
        }
    }
}
