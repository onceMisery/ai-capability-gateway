package com.ai.gateway.domain.port;

import com.ai.gateway.domain.model.AdminAction;
import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.Principal;

import java.util.List;

/**
 * Port for visibility authorization, execution authorization, and
 * administrative-operation authorization.
 *
 * <p>Specifies that authorization is executed in two passes for runtime
 * calls:</p>
 * <ol>
 * <li><b>Visibility authorization</b>: determines whether a capability
 * may enter the retrieval and model candidate set. Capabilities the
 * current Principal is not authorized for are excluded — their names,
 * descriptions, and existence are not exposed.</li>
 * <li><b>Execution authorization</b>: after parameters are fully bound,
 * a second authorization check is performed, potentially combining
 * resource attributes.</li>
 * </ol>
 *
 * <p>Control-plane (administrative) operations are gated separately by
 * {@link #authorizeAdmin(Principal, AdminAction)}.</p>
 *
 * <p>Default deny. Policy exceptions, dependency timeouts, or missing
 * claims must not degrade to allow. The policy version must be published
 * in coordination with the catalog snapshot to ensure permissions and
 * capabilities are consistent at runtime.</p>
 *
 * <p>In the initial release, authorization is optional:
 * all authenticated users may call all published read-only capabilities.
 * Visibility authorization degrades to authentication status check;
 * execution authorization degrades to Schema validation and Principal
 * parameter injection. This design is preserved for when the capability
 * surface grows or write operations are enabled.</p>
 *
 * <p>Adapters implementing this port query the authorization data source
 * (e.g., an RBAC/ABAC/ReBAC engine or a capability-role ACL table). The
 * port is a pure abstraction with no framework dependencies.</p>
 *
 * @see Principal
 * @see CapabilityManifest
 * @see AdminAction
 * @since 0.1.0
 */
public interface AuthorizationPort {

    /**
     * Filters the candidate capability list to only those visible to the
     * given principal (visibility authorization pass 1).
     *
     * <p>Capabilities the Principal is not authorized for are removed.
     * Their names, descriptions, and existence are not exposed to the
     * retrieval engine or the LLM. If the authorization data source is
     * unavailable, Fail Closed is adopted — no capabilities are returned.</p>
     *
     * @param principal the authenticated caller identity
     * @param candidates the full list of published capability manifests
     * @return the filtered list of visible capabilities; never {@code null}
     */
    List<CapabilityManifest> filterVisibleCapabilities(
            Principal principal, List<CapabilityManifest> candidates);

    /**
     * Determines whether the given principal is authorized to execute a
     * specific capability version (execution authorization
     * pass 2).
     *
     * <p>This check is performed after parameters are fully bound and may
     * combine resource attributes. Default deny: policy exceptions,
     * dependency timeouts, or missing claims must not degrade to allow.</p>
     *
     * @param principal the authenticated caller identity
     * @param capabilityId the capability identifier
     * @param version the capability semantic version
     * @return {@code true} if execution is authorized; {@code false} otherwise
     */
    boolean authorizeExecution(Principal principal, String capabilityId, String version);

    /**
     * Determines whether the given principal is authorized to perform a
     * control-plane administrative action (import, approve, publish,
     * rollback, or suspend).
     *
     * <p>Default deny: policy exceptions, dependency timeouts, or missing
     * claims must not degrade to allow.</p>
     *
     * @param principal the authenticated caller identity
     * @param action the administrative action being attempted
     * @return {@code true} if the action is authorized; {@code false} otherwise
     */
    boolean authorizeAdmin(Principal principal, AdminAction action);

    /**
     * 刷新 ACL 缓存
     *
     * <p>ACL 变更后调用，通知授权适配器重新加载数据库中的 ACL 条目。
     * 默认无操作，由持有 ACL 缓存的适配器覆盖实现。</p>
     *
     * @since 0.1.0
     */
    default void refreshAcl() {
        // 默认无操作
    }
}
