package com.ai.gateway.application.runtime;

import com.ai.gateway.domain.model.AuditPlane;
import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.CatalogSnapshot;
import com.ai.gateway.domain.model.ErrorCode;
import com.ai.gateway.domain.model.ExecutionPlan;
import com.ai.gateway.domain.model.Principal;
import com.ai.gateway.domain.model.PayloadLimits;
import com.ai.gateway.domain.model.RequestContext;
import com.ai.gateway.domain.model.ResiliencePolicy;
import com.ai.gateway.domain.model.RiskLevel;
import com.ai.gateway.domain.model.SystemContext;
import com.ai.gateway.domain.model.ValidationReport;
import com.ai.gateway.domain.port.AuthenticationPort;
import com.ai.gateway.domain.port.AuditPort;
import com.ai.gateway.domain.port.AuthorizationPort;
import com.ai.gateway.domain.port.CatalogPort;
import com.ai.gateway.domain.port.SchemaValidator;
import com.ai.gateway.domain.port.TypeConverterRegistry;
import com.ai.gateway.domain.service.ArgumentBinder;
import com.ai.gateway.domain.service.ManifestDigest;
import com.ai.gateway.domain.service.PayloadTreeGuard;
import com.ai.gateway.domain.service.PayloadLimitExceededException;
import com.ai.gateway.domain.service.Sha256Digest;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Deterministic structured invocation entry point.
 *
 * <p>This is the protocol-neutral runtime boundary for callers that already
 * selected a capability. Natural-language routing and future MCP/HTTP tool
 * adapters must converge on the same {@link DeterministicExecutionUseCase}
 * after this boundary has pinned the catalog snapshot, authorization decision,
 * schema and argument binding.</p>
 */
public final class StructuredInvocationUseCase {

    private final AuthenticationPort authenticationPort;
    private final AuthorizationPort authorizationPort;
    private final CatalogPort catalogPort;
    private final SchemaValidator schemaValidator;
    private final TypeConverterRegistry typeConverterRegistry;
    private final AuditPort auditPort;
    private final DeterministicExecutionUseCase deterministicExecutionUseCase;
    private final String environment;
    private final PayloadLimits payloadLimits;
    private final PayloadTreeGuard payloadTreeGuard;

    public StructuredInvocationUseCase(AuthenticationPort authenticationPort,
                                       AuthorizationPort authorizationPort,
                                       CatalogPort catalogPort,
                                       SchemaValidator schemaValidator,
                                       TypeConverterRegistry typeConverterRegistry,
                                       AuditPort auditPort,
                                       DeterministicExecutionUseCase deterministicExecutionUseCase,
                                       String environment) {
        this(authenticationPort, authorizationPort, catalogPort, schemaValidator,
                typeConverterRegistry, auditPort, deterministicExecutionUseCase, environment,
                PayloadLimits.defaults());
    }

    /**
     * 使用统一 Payload 预算创建结构化调用用例。
     *
     * @param authenticationPort 认证端口
     * @param authorizationPort 授权端口
     * @param catalogPort 能力目录端口
     * @param schemaValidator Schema 校验器
     * @param typeConverterRegistry 类型转换器注册表
     * @param auditPort 审计端口
     * @param deterministicExecutionUseCase 确定性执行用例
     * @param environment 运行环境
     * @param payloadLimits 统一 Payload 预算
     */
    public StructuredInvocationUseCase(AuthenticationPort authenticationPort,
                                       AuthorizationPort authorizationPort,
                                       CatalogPort catalogPort,
                                       SchemaValidator schemaValidator,
                                       TypeConverterRegistry typeConverterRegistry,
                                       AuditPort auditPort,
                                       DeterministicExecutionUseCase deterministicExecutionUseCase,
                                       String environment,
                                       PayloadLimits payloadLimits) {
        this.authenticationPort = Objects.requireNonNull(authenticationPort);
        this.authorizationPort = Objects.requireNonNull(authorizationPort);
        this.catalogPort = Objects.requireNonNull(catalogPort);
        this.schemaValidator = Objects.requireNonNull(schemaValidator);
        this.typeConverterRegistry = Objects.requireNonNull(typeConverterRegistry);
        this.auditPort = Objects.requireNonNull(auditPort);
        this.deterministicExecutionUseCase = Objects.requireNonNull(deterministicExecutionUseCase);
        this.environment = Objects.requireNonNull(environment);
        this.payloadLimits = Objects.requireNonNull(payloadLimits);
        this.payloadTreeGuard = new PayloadTreeGuard(this.payloadLimits);
        if (environment.isBlank()) {
            throw new IllegalArgumentException("environment must not be blank");
        }
    }

    /** Invokes a read-only capability using the pinned active snapshot. */
    public Result invoke(RequestContext requestContext, String requestId,
                         String capabilityId, String capabilityVersion,
                         Map<String, Object> modelArguments, String locale) {
        Objects.requireNonNull(requestContext);
        requireText(requestId, "requestId");
        requireText(capabilityId, "capabilityId");
        requireText(capabilityVersion, "capabilityVersion");
        Objects.requireNonNull(modelArguments, "modelArguments");
        requireText(locale, "locale");

        Principal principal;
        try {
            principal = authenticationPort.authenticate(requestContext);
        } catch (RuntimeException e) {
            return error(ErrorCode.AUTHENTICATION_FAILED, "Authentication failed", 0L);
        }
        CatalogSnapshot snapshot = catalogPort.loadCurrentSnapshot(environment);
        if (snapshot == null || snapshot.snapshotVersion() <= 0) {
            return error(ErrorCode.CAPABILITY_UNAVAILABLE, "Capability catalog unavailable", 0L);
        }
        CapabilityManifest manifest = snapshot.capabilities().stream()
                .filter(candidate -> capabilityId.equals(candidate.metadata().id())
                        && capabilityVersion.equals(candidate.metadata().version()))
                .findFirst().orElse(null);
        if (manifest == null) {
            return error(ErrorCode.NO_CAPABILITY_MATCH, "Capability not found", snapshot.snapshotVersion());
        }
        List<CapabilityManifest> visible = authorizationPort.filterVisibleCapabilities(
                principal, List.of(manifest));
        if (visible == null || visible.isEmpty()) {
            return error(ErrorCode.PERMISSION_DENIED, "Capability is not available", snapshot.snapshotVersion());
        }
        if (manifest.spec().risk() != RiskLevel.READ_ONLY) {
            return error(ErrorCode.CONFIRMATION_REQUIRED,
                    "Write capabilities require the Prepare/Confirm protocol", snapshot.snapshotVersion());
        }

        return invokeResolved(requestId, principal, snapshot, manifest, modelArguments, locale);
    }

    /**
     * Invokes a manifest already pinned by a trusted in-memory catalog view.
     * Authentication and visibility selection must happen before this boundary;
     * full Schema validation and execution authorization still happen here.
     */
    public Result invokeResolved(String requestId,
                                 Principal principal,
                                 CatalogSnapshot snapshot,
                                 CapabilityManifest manifest,
                                 Map<String, Object> modelArguments,
                                 String locale) {
        return invokeResolved(requestId, principal, snapshot, manifest, modelArguments,
                locale, AuditPlane.STRUCTURED);
    }

    /**
     * 在指定入口平面下调用已固定的能力清单。
     *
     * <p>平面仅用于审计与成本归属：Agent、MCP、A2A 与结构化直调共用同一条确定性执行链，
     * 若不区分平面，某一入口的故障率会被其他入口的流量稀释。安全判定与本参数无关。</p>
     *
     * @param requestId 入站请求标识
     * @param principal 已认证主体
     * @param snapshot 已固定的目录快照
     * @param manifest 已固定的能力清单
     * @param modelArguments 模型/调用方提交的公开参数
     * @param locale 语言标签
     * @param plane 发起本次调用的入口平面
     * @return 调用结果
     */
    public Result invokeResolved(String requestId,
                                 Principal principal,
                                 CatalogSnapshot snapshot,
                                 CapabilityManifest manifest,
                                 Map<String, Object> modelArguments,
                                 String locale,
                                 AuditPlane plane) {
        requireText(requestId, "requestId");
        Objects.requireNonNull(principal, "principal must not be null");
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        Objects.requireNonNull(manifest, "manifest must not be null");
        Objects.requireNonNull(modelArguments, "modelArguments must not be null");
        requireText(locale, "locale");
        Objects.requireNonNull(plane, "plane must not be null");
        String capabilityId = manifest.metadata().id();
        String capabilityVersion = manifest.metadata().version();
        if (manifest.spec().risk() != RiskLevel.READ_ONLY) {
            return error(ErrorCode.CONFIRMATION_REQUIRED,
                    "Write capabilities require the Prepare/Confirm protocol",
                    snapshot.snapshotVersion());
        }
        try {
            auditPort.recordAccepted(
                    requestId, Sha256Digest.sha256Hex(principal.subject()), principal.orgId());
        } catch (RuntimeException e) {
            return error(ErrorCode.AUTHENTICATION_FAILED,
                    "Audit persistence failed", snapshot.snapshotVersion());
        }

        ValidationReport report;
        try {
            payloadTreeGuard.validateInput(modelArguments);
            report = schemaValidator.validate(modelArguments, manifest.spec().inputSchema());
        } catch (PayloadLimitExceededException e) {
            return error(ErrorCode.ARGUMENT_VALIDATION_FAILED,
                    "Arguments exceed the configured payload budget", snapshot.snapshotVersion());
        } catch (RuntimeException e) {
            return error(ErrorCode.INVALID_MODEL_OUTPUT, "Input validation unavailable", snapshot.snapshotVersion());
        }
        if (report == null || !report.valid()) {
            return error(ErrorCode.ARGUMENT_VALIDATION_FAILED,
                    "Arguments failed validation", snapshot.snapshotVersion());
        }
        if (!authorizationPort.authorizeExecution(
                principal, capabilityId, capabilityVersion)) {
            return error(ErrorCode.PERMISSION_DENIED, "Execution authorization denied",
                    snapshot.snapshotVersion());
        }

        String manifestDigest = ManifestDigest.sha256(manifest);
        String executionId = UUID.randomUUID().toString();
        String principalDigest = Sha256Digest.sha256Hex(principal.subject());
        SystemContext context = new SystemContext(
                requestId, Instant.now().plusSeconds(15).toEpochMilli(), null, locale);
        List<Object> boundArguments;
        try {
            boundArguments = new ArgumentBinder(typeConverterRegistry, schemaValidator,
                    principal, context, manifest, payloadLimits).bind(modelArguments);
        } catch (RuntimeException e) {
            return error(ErrorCode.ARGUMENT_VALIDATION_FAILED,
                    "Arguments failed binding", snapshot.snapshotVersion());
        }
        ResiliencePolicy policy = manifest.spec().resilience() != null
                ? manifest.spec().resilience() : new ResiliencePolicy(15_000, 0, 1, true);
        ExecutionPlan plan = new ExecutionPlan(executionId, principalDigest,
                snapshot.snapshotVersion(), capabilityId, capabilityVersion, manifestDigest,
                modelArguments, boundArguments, "policy-" + executionId,
                manifest.spec().risk(), policy);
        DeterministicExecutionUseCase.ExecutionResult execution =
                deterministicExecutionUseCase.execute(requestId, plan, principal, manifest, plane);
        if (execution.errorCode() != null) {
            return new Result(Status.ERROR, execution.data(), execution.errorCode(),
                    execution.summary(), snapshot.snapshotVersion(), capabilityId, capabilityVersion);
        }
        return new Result(Status.COMPLETED, execution.data(), null, execution.summary(),
                snapshot.snapshotVersion(), capabilityId, capabilityVersion);
    }

    /** Returns only capabilities visible to the authenticated principal. */
    public List<Tool> listTools(RequestContext requestContext) {
        Principal principal = authenticationPort.authenticate(requestContext);
        CatalogSnapshot snapshot = catalogPort.loadCurrentSnapshot(environment);
        if (snapshot == null || snapshot.snapshotVersion() <= 0) {
            return List.of();
        }
        List<CapabilityManifest> visible = authorizationPort.filterVisibleCapabilities(
                principal, snapshot.capabilities());
        if (visible == null) {
            return List.of();
        }
        return visible.stream().map(manifest -> new Tool(
                manifest.metadata().id(), manifest.metadata().version(),
                manifest.spec().displayName(), manifest.spec().description(),
                manifest.spec().risk().name(), snapshot.snapshotVersion())).toList();
    }

    private Result error(ErrorCode code, String message, long snapshotVersion) {
        return new Result(Status.ERROR, null, code.name(), message, snapshotVersion, null, null);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    public enum Status { COMPLETED, ERROR }

    public record Result(Status status, Map<String, Object> data, String errorCode,
                         String message, long snapshotVersion,
                         String capabilityId, String capabilityVersion) {
    }

    public record Tool(String capabilityId, String version, String displayName,
                       String description, String risk, long snapshotVersion) {
    }
}
