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

import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Sa-Token reference implementation of {@link AuthorizationPort} enforcing
 * capability-level (capabilityId + version) authorization.
 *
 * <p>The core decision matches the caller's roles against a
 * capability → allowed-roles access-control list (ACL). Authorization is
 * keyed by capability id and version. Production wiring loads ACL entries
 * from PostgreSQL through {@link AclRepository}; the map is only a process
 * cache.</p>
 *
 * <p>Empty ACL behavior is explicit. Production wiring uses default-deny;
 * development-only callers may opt into allow-all with the boolean
 * constructor argument. ACL loading failures always deny.</p>
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
    private volatile Map<CapabilityKey, AclPolicy> capabilityAcl = Map.of();
    private volatile boolean aclLoadHealthy = true;

    /**
     * Constructs an adapter with the secure default policy (deny when the ACL
     * is empty).
     */
    public SaTokenAuthorizationAdapter() {
        this(false);
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

        if (!aclLoadHealthy) {
            return false;
        }
        // Wildcard is only meaningful after the ACL source has loaded
        // successfully. Infrastructure failure must remain fail-closed.
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

    @Override
    public AclPolicyStatus aclPolicyStatus() {
        return new AclPolicyStatus(
                aclLoadHealthy,
                capabilityAcl.size(),
                allowAllIfAclEmpty ? "ALLOW" : "DENY");
    }

    /**
     * Registers an ACL entry granting the given roles access to a capability.
     *
     * @param capabilityId the capability identifier
     * @param roles the roles allowed to execute the capability
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
     * Loads the capability ACL.
     *
     * <p>If an {@link AclRepository} is configured, loads entries from
     * PostgreSQL. Without a repository the ACL remains empty and the
     * explicit empty-ACL policy decides the result; loading failures deny.</p>
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
     * Refreshes the in-memory ACL cache from the database.
     *
     * <p>Called after ACL mutations from the admin console to ensure
     * authorization decisions reflect the latest configuration.</p>
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
