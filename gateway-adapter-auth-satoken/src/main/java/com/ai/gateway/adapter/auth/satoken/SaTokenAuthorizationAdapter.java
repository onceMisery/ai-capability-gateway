package com.ai.gateway.adapter.auth.satoken;

import com.ai.gateway.domain.model.AdminAction;
import com.ai.gateway.domain.model.CapabilityAclEntry;
import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.Principal;
import com.ai.gateway.domain.port.AclRepository;
import com.ai.gateway.domain.port.AuthorizationPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sa-Token reference implementation of {@link AuthorizationPort} enforcing
 * capability-level (capabilityId) authorization.
 *
 * <p>The core decision matches the caller's roles against a
 * capability → allowed-roles access-control list (ACL). Per the
 * tech-selection decision, authorization granularity is the capability id;
 * the ACL is intended to be backed by a PostgreSQL table maintained through
 * the admin API. This reference implementation keeps the ACL in memory and
 * seeds it programmatically; a production adapter would replace
 * {@link #loadAcl()} with a database query.</p>
 *
 * <p>Initial-release degradation: when {@code allowAllIfAclEmpty} is
 * {@code true} (the default) and no ACL entries are configured, all
 * authenticated callers are authorized — mirroring the spec's initial
 * release rule that authorization is optional. When ACL entries exist, the
 * decision is default-deny.</p>
 *
 * @since 0.1.0
 */
public class SaTokenAuthorizationAdapter implements AuthorizationPort {

    private static final Logger log = LoggerFactory.getLogger(SaTokenAuthorizationAdapter.class);

    /**
     * Role that grants every administrative action.
     */
    public static final String ROLE_ADMIN = "admin";

    /**
     * Permission wildcard that grants every capability execution.
     */
    public static final String PERMISSION_WILDCARD = "*";

    private final boolean allowAllIfAclEmpty;
    private final AclRepository aclRepository;

    /**
     * Capability ACL: capabilityId → set of roles allowed to execute it.
     */
    private final Map<CapabilityKey, AclPolicy> capabilityAcl = new ConcurrentHashMap<>();
    private volatile boolean aclLoadHealthy = true;

    /**
     * Constructs an adapter with the initial-release default policy
     * (allow all authenticated callers when the ACL is empty).
     */
    public SaTokenAuthorizationAdapter() {
        this(true);
    }

    /**
     * Constructs an adapter with an explicit empty-ACL policy.
     *
     * @param allowAllIfAclEmpty when {@code true}, an empty ACL authorizes
     * every authenticated caller; when {@code false}, an empty ACL denies
     * everything (strict default-deny)
     */
    public SaTokenAuthorizationAdapter(boolean allowAllIfAclEmpty) {
        this(allowAllIfAclEmpty, null);
    }

    /**
     * Constructs an adapter with an AclRepository for loading ACL entries
     * from PostgreSQL.
     *
     * @param allowAllIfAclEmpty the empty-ACL policy
     * @param aclRepository the ACL repository; may be {@code null} for stub mode
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

        // Wildcard permission short-circuits the ACL check.
        if (principal.permissions().contains(PERMISSION_WILDCARD)) {
            return true;
        }
        if (!aclLoadHealthy) {
            return false;
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
                || principal.permissions().containsAll(policy.requiredPermissions());
        return roleAllowed && permissionsAllowed;
    }

    @Override
    public boolean authorizeAdmin(Principal principal, AdminAction action) {
        Objects.requireNonNull(principal, "principal must not be null");
        Objects.requireNonNull(action, "action must not be null");
        // Administrative actions require the admin role or the wildcard
        // permission; default deny otherwise.
        return principal.roles().contains(ROLE_ADMIN)
                || principal.permissions().contains(PERMISSION_WILDCARD);
    }

    /**
     * Registers an ACL entry granting the given roles access to a capability.
     *
     * @param capabilityId the capability identifier
     * @param roles the roles allowed to execute the capability
     */
    public void grant(String capabilityId, Set<String> roles) {
        Objects.requireNonNull(capabilityId, "capabilityId must not be null");
        Objects.requireNonNull(roles, "roles must not be null");
        capabilityAcl.put(new CapabilityKey(capabilityId, "*"),
                new AclPolicy(Set.copyOf(roles), Set.of()));
    }

    public void grant(String capabilityId, String version, Set<String> roles,
                      Set<String> requiredPermissions) {
        capabilityAcl.put(new CapabilityKey(capabilityId, version),
                new AclPolicy(Set.copyOf(roles), Set.copyOf(requiredPermissions)));
    }

    /**
     * Loads the capability ACL.
     *
     * <p>If an {@link AclRepository} is configured, loads entries from
     * PostgreSQL. Otherwise, leaves the ACL empty, which under the
     * default {@code allowAllIfAclEmpty} policy authorizes all authenticated
     * callers (initial-release behavior).</p>
     */
    protected void loadAcl() {
        if (aclRepository == null) {
            return;
        }
        try {
            List<CapabilityAclEntry> entries = aclRepository.findAllAclEntries();
            for (CapabilityAclEntry entry : entries) {
                capabilityAcl.put(
                        new CapabilityKey(entry.capabilityId(), entry.capabilityVersion()),
                        new AclPolicy(Set.copyOf(entry.allowedRoles()),
                                Set.copyOf(entry.requiredPermissions())));
            }
            aclLoadHealthy = true;
            log.info("Loaded {} ACL entries from database", entries.size());
        } catch (Exception e) {
            aclLoadHealthy = false;
            log.error("Failed to load ACL entries from database; authorization fails closed", e);
        }
    }

    /**
     * Refreshes the in-memory ACL cache from the database.
     *
     * <p>Called after ACL mutations from the admin console to ensure
     * authorization decisions reflect the latest configuration.</p>
     */
    public void refreshAcl() {
        capabilityAcl.clear();
        loadAcl();
        log.info("ACL cache refreshed, {} entries loaded", capabilityAcl.size());
    }

    private record CapabilityKey(String capabilityId, String version) {
    }

    private record AclPolicy(Set<String> allowedRoles, Set<String> requiredPermissions) {
    }
}
