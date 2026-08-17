package com.ai.gateway.application.runtime;

import com.ai.gateway.domain.model.DeadlineBudget;
import com.ai.gateway.domain.model.ErrorCode;
import com.ai.gateway.domain.model.ExecutionPlan;
import com.ai.gateway.domain.model.InvocationRequest;
import com.ai.gateway.domain.model.InvocationResult;
import com.ai.gateway.domain.model.PayloadLimits;
import com.ai.gateway.domain.model.SystemContext;
import com.ai.gateway.domain.port.AuditPort;
import com.ai.gateway.domain.port.AuthorizationPort;
import com.ai.gateway.domain.port.InvocationAdapter;
import com.ai.gateway.domain.port.SchemaValidator;
import com.ai.gateway.domain.port.TypeConverterRegistry;
import com.ai.gateway.domain.service.ArgumentBinder;
import com.ai.gateway.domain.service.DeadlineBudgetManager;
import com.ai.gateway.domain.service.RedactionService;
import com.ai.gateway.domain.service.ResultNormalizer;
import com.ai.gateway.domain.service.PayloadLimitExceededException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Use case for deterministic execution of a resolved capability
 * (Section 11 of the design document).
 *
 * <p>The deterministic execution pipeline consists of three phases:</p>
 * <ol>
 * <li><b>Parameter processing</b>:
 * <ol>
 * <li>Parse model output JSON, rejecting duplicate keys and non-finite
 * numbers.</li>
 * <li>Validate against the public input Schema.</li>
 * <li>Execute format/length/enum/business pre-constraints.</li>
 * <li>Resolve non-model fields from Principal and Manifest constants.</li>
 * <li>Construct protocol parameters by static mapping.</li>
 * <li>Execute type and size validation on complete parameters.</li>
 * <li>Authorization is done by the caller (not the binder).</li>
 * <li>Protocol adapter call is done by the caller (not the binder).</li>
 * </ol>
 * </li>
 * <li><b>Protocol invocation</b>: the {@link InvocationAdapter} invokes the
 * target Provider using the fully-bound, positionally-ordered arguments.</li>
 * <li><b>Result governance</b>:
 * <ol>
 * <li>Convert the protocol result to a JSON-compatible neutral tree.</li>
 * <li>Check response size, depth, collection length, and processing time.</li>
 * <li>Determine business success by envelope rules and extract data.</li>
 * <li>Construct the public result by projection whitelist.</li>
 * <li>Execute field redaction.</li>
 * <li>Validate the public output Schema.</li>
 * <li>Generate the structured result.</li>
 * <li>Optional natural-language summary (caller's responsibility).</li>
 * </ol>
 * </li>
 * </ol>
 *
 * <p>Note: {@link ArgumentBinder} and {@link ResultNormalizer} are per-request
 * domain services. This use case receives the shared dependencies
 * ({@link TypeConverterRegistry} and {@link RedactionService}) via constructor
 * injection and constructs per-request instances using the
 * {@link ExecutionPlan}'s context.</p>
 *
 * <p>This class uses constructor injection and contains no Spring annotations.
 * It is thread-safe: it holds no mutable per-request state.</p>
 *
 * @see InvocationAdapter
 * @see ArgumentBinder
 * @see ResultNormalizer
 * @since 0.1.0
 */
public final class DeterministicExecutionUseCase {

    private static final Logger log = LoggerFactory.getLogger(DeterministicExecutionUseCase.class);

    private final InvocationAdapter invocationAdapter;
    private final TypeConverterRegistry typeConverterRegistry;
    private final RedactionService redactionService;
    private final SchemaValidator schemaValidator;
    private final AuthorizationPort authorizationPort;
    private final AuditPort auditPort;
    private final DeadlineBudgetManager deadlineBudgetManager;
    private final PayloadLimits payloadLimits;

    /**
     * Constructs a new DeterministicExecutionUseCase with the required
     * dependencies.
     *
     * <p>Note: {@code typeConverterRegistry} is the shared dependency for
     * constructing per-request {@link ArgumentBinder} instances, and
     * {@code redactionService} is the shared dependency for constructing
     * per-request {@link ResultNormalizer} instances. {@code schemaValidator}
     * is shared by both per-request services.</p>
     *
     * @param invocationAdapter the protocol invocation adapter
     * @param typeConverterRegistry the type converter registry for ArgumentBinder
     * @param redactionService the redaction service for ResultNormalizer
     * @param schemaValidator the JSON Schema validator
     * @param authorizationPort the authorization port for re-authorization
     * @param auditPort the audit port for event recording
     * @param deadlineBudgetManager the deadline budget manager
     * @throws NullPointerException if any argument is null
     */
    public DeterministicExecutionUseCase(InvocationAdapter invocationAdapter,
                                          TypeConverterRegistry typeConverterRegistry,
                                          RedactionService redactionService,
                                          SchemaValidator schemaValidator,
                                          AuthorizationPort authorizationPort,
                                          AuditPort auditPort,
                                          DeadlineBudgetManager deadlineBudgetManager) {
        this(invocationAdapter, typeConverterRegistry, redactionService, schemaValidator,
                authorizationPort, auditPort, deadlineBudgetManager, PayloadLimits.defaults());
    }

    /**
     * 使用统一 Payload 预算创建确定性执行用例。
     *
     * @param invocationAdapter 协议调用适配器
     * @param typeConverterRegistry 类型转换器注册表
     * @param redactionService 脱敏服务
     * @param schemaValidator Schema 校验器
     * @param authorizationPort 授权端口
     * @param auditPort 审计端口
     * @param deadlineBudgetManager 截止时间预算管理器
     * @param payloadLimits 输入/输出 Payload 预算
     */
    public DeterministicExecutionUseCase(InvocationAdapter invocationAdapter,
                                          TypeConverterRegistry typeConverterRegistry,
                                          RedactionService redactionService,
                                          SchemaValidator schemaValidator,
                                          AuthorizationPort authorizationPort,
                                          AuditPort auditPort,
                                          DeadlineBudgetManager deadlineBudgetManager,
                                          PayloadLimits payloadLimits) {
        this.invocationAdapter = java.util.Objects.requireNonNull(invocationAdapter,
                "invocationAdapter must not be null");
        this.typeConverterRegistry = java.util.Objects.requireNonNull(typeConverterRegistry,
                "typeConverterRegistry must not be null");
        this.redactionService = java.util.Objects.requireNonNull(redactionService,
                "redactionService must not be null");
        this.schemaValidator = java.util.Objects.requireNonNull(schemaValidator,
                "schemaValidator must not be null");
        this.authorizationPort = java.util.Objects.requireNonNull(authorizationPort,
                "authorizationPort must not be null");
        this.auditPort = java.util.Objects.requireNonNull(auditPort,
                "auditPort must not be null");
        this.deadlineBudgetManager = java.util.Objects.requireNonNull(deadlineBudgetManager,
                "deadlineBudgetManager must not be null");
        this.payloadLimits = java.util.Objects.requireNonNull(payloadLimits,
                "payloadLimits must not be null");
    }

    /**
     * Executes the given execution plan through the parameter processing,
     * protocol invocation, and result governance phases (Section 11).
     *
     * @param plan the execution plan containing the validated model arguments,
     * resolved protocol arguments, and resilience policy
     * @param principal the executing principal (for re-authorization)
     * @param manifest the capability manifest (for argument binding and result normalization)
     * @return the execution result
     * @throws NullPointerException if any argument is null
     */
    public ExecutionResult execute(ExecutionPlan plan,
                                    com.ai.gateway.domain.model.Principal principal,
                                    com.ai.gateway.domain.model.CapabilityManifest manifest) {
        return execute(UUID.randomUUID().toString(), plan, principal, manifest);
    }

    /** Executes using the inbound request identifier for audit and trace correlation. */
    public ExecutionResult execute(String requestId, ExecutionPlan plan,
                                    com.ai.gateway.domain.model.Principal principal,
                                    com.ai.gateway.domain.model.CapabilityManifest manifest) {
        java.util.Objects.requireNonNull(requestId, "requestId must not be null");
        java.util.Objects.requireNonNull(plan, "plan must not be null");
        java.util.Objects.requireNonNull(principal, "principal must not be null");
        java.util.Objects.requireNonNull(manifest, "manifest must not be null");
        long startTime = System.currentTimeMillis();
        log.info("Deterministic execution started: executionId={}, capability={}",
                plan.executionId(), plan.capabilityId());

        // --- Phase 1: Parameter processing ---
        // Steps 1-6 are handled by ArgumentBinder; steps 7-8 by the caller.
        // The resolvedProtocolArguments in the plan are already bound.
        // For re-binding from model arguments, construct a per-request ArgumentBinder.
        List<Object> boundArguments;
        try {
            // The plan already contains resolvedProtocolArguments from the routing phase.
            // Use them directly — the binding was done during routing.
            boundArguments = plan.resolvedProtocolArguments();
        } catch (Exception e) {
            log.error("Parameter processing failed: capability={}", plan.capabilityId());
            auditPort.recordTerminal(requestId, plan.capabilityId(),
                    plan.capabilityVersion(),
                    ErrorCode.ARGUMENT_VALIDATION_FAILED.name(),
                    System.currentTimeMillis() - startTime,
                    "{\"reason\":\"parameter_processing_failed\"}");
            return new ExecutionResult(null,
                    ErrorCode.ARGUMENT_VALIDATION_FAILED.name(),
                    "Parameter processing failed");
        }

        // Step 7: Re-execute authorization
        boolean authorized;
        try {
            authorized = authorizationPort.authorizeExecution(
                    principal, plan.capabilityId(), plan.capabilityVersion());
        } catch (Exception e) {
            log.error("Execution authorization data source failed: capability={}",
                    plan.capabilityId());
            auditPort.recordTerminal(requestId, plan.capabilityId(),
                    plan.capabilityVersion(), ErrorCode.PERMISSION_DENIED.name(),
                    System.currentTimeMillis() - startTime,
                    "{\"reason\":\"authorization_unavailable\"}");
            return new ExecutionResult(null,
                    ErrorCode.PERMISSION_DENIED.name(),
                    "Authorization data source unavailable");
        }
        if (!authorized) {
            log.warn("Execution authorization denied: capability={}", plan.capabilityId());
            auditPort.recordTerminal(requestId, plan.capabilityId(),
                    plan.capabilityVersion(),
                    ErrorCode.PERMISSION_DENIED.name(),
                    System.currentTimeMillis() - startTime,
                    "{}");
            return new ExecutionResult(null,
                    ErrorCode.PERMISSION_DENIED.name(),
                    "Execution authorization denied");
        }

        // --- Phase 2: Protocol invocation ---
        // Record STARTED audit event
        auditPort.recordStarted(requestId, plan.capabilityId(),
                plan.capabilityVersion(), plan.manifestDigest());

        // Construct the invocation request
        long providerTimeout = plan.resiliencePolicy().timeoutMs();
        DeadlineBudget budget = deadlineBudgetManager.createBudget(providerTimeout);
        long safeTimeout = deadlineBudgetManager.remainingForProvider(budget);

        SystemContext systemContext = new SystemContext(
                requestId,
                java.time.Instant.now().toEpochMilli() + safeTimeout,
                null,
                "zh-CN"
        );

        InvocationRequest invocationRequest = new InvocationRequest(
                plan.capabilityId(),
                plan.capabilityVersion(),
                plan.manifestDigest(),
                new DeadlineBudget(safeTimeout, safeTimeout),
                null,
                systemContext,
                boundArguments
        );

        InvocationResult invocationResult;
        try {
            invocationResult = invocationAdapter.invoke(invocationRequest);
        } catch (Exception e) {
            log.error("Protocol invocation failed: capability={}", plan.capabilityId());
            auditPort.recordTerminal(requestId, plan.capabilityId(),
                    plan.capabilityVersion(),
                    ErrorCode.PROTOCOL_ERROR.name(),
                    System.currentTimeMillis() - startTime,
                    "{\"reason\":\"provider_invocation_failed\"}");
            return new ExecutionResult(null,
                    ErrorCode.PROTOCOL_ERROR.name(),
                    "Protocol invocation failed");
        }

        // Check for protocol-level errors
        if (invocationResult.errorCode() != null) {
            log.warn("Provider returned error: code={}", invocationResult.errorCode());
            auditPort.recordTerminal(requestId, plan.capabilityId(),
                    plan.capabilityVersion(),
                    invocationResult.errorCode().name(),
                    System.currentTimeMillis() - startTime,
                    "{\"protocolStatus\":\"" + invocationResult.protocolStatus() + "\"}");
            return new ExecutionResult(null,
                    invocationResult.errorCode().name(),
                    safeProviderErrorMessage(invocationResult.errorCode()));
        }

        // --- Phase 3: Result governance ---
        Map<String, Object> normalizedResult;
        try {
            // Construct a per-request ResultNormalizer with the manifest's output contract
            ResultNormalizer normalizer = new ResultNormalizer(
                    manifest.spec().output(),
                    schemaValidator,
                    redactionService,
                    payloadLimits
            );
            // Steps 1-7: normalize the invocation result
            normalizedResult = normalizer.normalize(invocationResult);
        } catch (PayloadLimitExceededException e) {
            log.warn("Result exceeds Payload budget: capability={}", plan.capabilityId());
            auditPort.recordTerminal(requestId, plan.capabilityId(),
                    plan.capabilityVersion(), ErrorCode.RESULT_TOO_LARGE.name(),
                    System.currentTimeMillis() - startTime,
                    "{\"reason\":\"payload_budget_exceeded\"}");
            return new ExecutionResult(null,
                    ErrorCode.RESULT_TOO_LARGE.name(),
                    "Provider result exceeds the configured payload budget");
        } catch (Exception e) {
            log.error("Result governance failed: capability={}", plan.capabilityId());
            auditPort.recordTerminal(requestId, plan.capabilityId(),
                    plan.capabilityVersion(),
                    ErrorCode.PROTOCOL_ERROR.name(),
                    System.currentTimeMillis() - startTime,
                    "{\"reason\":\"result_governance_failed\"}");
            return new ExecutionResult(null,
                    ErrorCode.PROTOCOL_ERROR.name(),
                    "Result governance failed");
        }

        // Record the terminal audit event
        long durationMs = System.currentTimeMillis() - startTime;
        auditPort.recordTerminal(requestId, plan.capabilityId(),
                plan.capabilityVersion(), "SUCCEEDED", durationMs,
                "{\"snapshotVersion\":" + plan.snapshotVersion() + "}");

        log.info("Deterministic execution completed: executionId={}, durationMs={}",
                plan.executionId(), durationMs);

        // Step 8: Optional natural-language summary (caller's responsibility)
        String summary = "Capability " + plan.capabilityId() + " executed successfully";

        return new ExecutionResult(normalizedResult, null, summary);
    }

    private String safeProviderErrorMessage(ErrorCode errorCode) {
        return errorCode == ErrorCode.RATE_LIMITED
                ? "Provider rate limit reached"
                : "Provider invocation failed";
    }

    /**
     * The result of a deterministic execution.
     *
     * @param data the normalized result data; null on error
     * @param errorCode the stable error code; null on success
     * @param summary a human-readable summary
     */
    public record ExecutionResult(
            Map<String, Object> data,
            String errorCode,
            String summary
    ) {
    }
}
