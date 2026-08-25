package com.ai.gateway.application.runtime;

import com.ai.gateway.domain.model.*;
import com.ai.gateway.domain.port.AuthorizationPort;
import com.ai.gateway.domain.port.CandidateRetriever;
import com.ai.gateway.domain.port.LlmRouterPort;
import com.ai.gateway.domain.port.SchemaValidator;
import com.ai.gateway.domain.port.TypeConverterRegistry;
import com.ai.gateway.domain.service.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Handles the post-threshold SELECT flow.
 *
 * <p>The routing use case owns pipeline orchestration while this component
 * owns model decision processing, argument binding, authorization and
 * execution-plan creation. Replacing this component is the extension point
 * for alternative SELECT policies.</p>
 */
public final class DefaultSelectDecisionProcessor implements SelectDecisionProcessor {

    private final AuthorizationPort authorizationPort;
    private final LlmRouterPort llmRouterPort;
    private final SchemaValidator schemaValidator;
    private final AliasGenerator aliasGenerator;
    private final TypeConverterRegistry typeConverterRegistry;
    private final DeterministicExecutionUseCase
            deterministicExecutionUseCase;
    private final PayloadLimits payloadLimits;
    private final PayloadTreeGuard payloadTreeGuard;

    public DefaultSelectDecisionProcessor(AuthorizationPort authorizationPort,
                                          LlmRouterPort llmRouterPort,
                                          SchemaValidator schemaValidator,
                                          com.ai.gateway.domain.service.AliasGenerator aliasGenerator,
                                          TypeConverterRegistry typeConverterRegistry,
                                          DeterministicExecutionUseCase deterministicExecutionUseCase,
                                          com.ai.gateway.domain.model.PayloadLimits payloadLimits) {
        this.authorizationPort = Objects.requireNonNull(authorizationPort,
                "authorizationPort must not be null");
        this.llmRouterPort = Objects.requireNonNull(llmRouterPort,
                "llmRouterPort must not be null");
        this.schemaValidator = Objects.requireNonNull(schemaValidator,
                "schemaValidator must not be null");
        this.aliasGenerator = Objects.requireNonNull(aliasGenerator,
                "aliasGenerator must not be null");
        this.typeConverterRegistry = Objects.requireNonNull(typeConverterRegistry,
                "typeConverterRegistry must not be null");
        this.deterministicExecutionUseCase = Objects.requireNonNull(
                deterministicExecutionUseCase, "deterministicExecutionUseCase must not be null");
        this.payloadLimits = Objects.requireNonNull(payloadLimits, "payloadLimits must not be null");
        this.payloadTreeGuard = new PayloadTreeGuard(payloadLimits);
    }

    @Override
    public NaturalLanguageQueryUseCase.QueryResult process(
            CandidateRetriever.ScoredCapability selected,
            Principal principal,
            long snapshotVersion,
            String requestId,
            String originalText,
            String locale,
            long startTime,
            TerminalRecorder terminalRecorder) {
        Objects.requireNonNull(selected, "selected must not be null");
        Objects.requireNonNull(principal, "principal must not be null");
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(originalText, "originalText must not be null");
        Objects.requireNonNull(locale, "locale must not be null");
        Objects.requireNonNull(terminalRecorder, "terminalRecorder must not be null");

        CapabilityManifest manifest = selected.capability();
        String alias = aliasGenerator.generate(
                snapshotVersion, manifest.metadata().id(), manifest.metadata().version());
        LlmRouterPort.LlmCandidate llmCandidate = new LlmRouterPort.LlmCandidate(
                alias,
                manifest.spec().displayName(),
                manifest.spec().description(),
                manifest.spec().examples().positive(),
                manifest.spec().examples().negative(),
                manifest.spec().examples().synonyms(),
                manifest.spec().inputSchema());

        ModelDecision decision;
        try {
            decision = llmRouterPort.route(originalText, List.of(llmCandidate));
        } catch (LlmRouterPort.LlmRoutingException e) {
            return terminalRecorder.record(new NaturalLanguageQueryUseCase.QueryResult(
                            NaturalLanguageQueryUseCase.QueryStatus.ERROR, null, null, null,
                            snapshotVersion, e.errorCode().name(), safeLlmErrorMessage(e.errorCode())),
                    principal, requestId, snapshotVersion, manifest, startTime);
        } catch (Exception e) {
            return terminalRecorder.record(new NaturalLanguageQueryUseCase.QueryResult(
                            NaturalLanguageQueryUseCase.QueryStatus.ERROR, null, null, null,
                            snapshotVersion, ErrorCode.PROTOCOL_ERROR.name(), "LLM routing failed"),
                    principal, requestId, snapshotVersion, manifest, startTime);
        }

        if (decision instanceof ModelDecision.NoMatchDecision noMatch) {
            return terminalRecorder.record(new NaturalLanguageQueryUseCase.QueryResult(
                            NaturalLanguageQueryUseCase.QueryStatus.NO_MATCH, null, null, null,
                            snapshotVersion, ErrorCode.NO_CAPABILITY_MATCH.name(),
                            "LLM returned NO_MATCH: " + noMatch.reasonCode()),
                    principal, requestId, snapshotVersion, manifest, startTime);
        }
        if (decision instanceof ModelDecision.ClarifyDecision clarify) {
            return terminalRecorder.clarification(
                    List.of(selected), clarify.question(), principal, snapshotVersion,
                    requestId, startTime);
        }
        if (!(decision instanceof ModelDecision.SelectDecision select)) {
            return terminalRecorder.record(new NaturalLanguageQueryUseCase.QueryResult(
                            NaturalLanguageQueryUseCase.QueryStatus.ERROR, null, null, null,
                            snapshotVersion, ErrorCode.INVALID_MODEL_OUTPUT.name(),
                            "Unknown model decision type"),
                    principal, requestId, snapshotVersion, manifest, startTime);
        }
        if (!select.alias().equals(alias)) {
            return terminalRecorder.record(new NaturalLanguageQueryUseCase.QueryResult(
                            NaturalLanguageQueryUseCase.QueryStatus.ERROR, null, null, null,
                            snapshotVersion, ErrorCode.INVALID_MODEL_OUTPUT.name(),
                            "Selected alias not in candidate set"),
                    principal, requestId, snapshotVersion, manifest, startTime);
        }

        try {
            payloadTreeGuard.validateInput(select.arguments());
            var schemaReport = schemaValidator.validate(
                    select.arguments(), manifest.spec().inputSchema());
            if (!schemaReport.valid()) {
                return terminalRecorder.record(new NaturalLanguageQueryUseCase.QueryResult(
                                NaturalLanguageQueryUseCase.QueryStatus.ERROR, null, null, null,
                                snapshotVersion, ErrorCode.INVALID_MODEL_OUTPUT.name(),
                                "Model output failed schema validation"),
                        principal, requestId, snapshotVersion, manifest, startTime);
            }
        } catch (PayloadLimitExceededException e) {
            return terminalRecorder.record(new NaturalLanguageQueryUseCase.QueryResult(
                            NaturalLanguageQueryUseCase.QueryStatus.ERROR, null, null, null,
                            snapshotVersion, ErrorCode.ARGUMENT_VALIDATION_FAILED.name(),
                            "Model arguments exceed the configured payload budget"),
                    principal, requestId, snapshotVersion, manifest, startTime);
        } catch (Exception e) {
            return terminalRecorder.record(new NaturalLanguageQueryUseCase.QueryResult(
                            NaturalLanguageQueryUseCase.QueryStatus.ERROR, null, null, null,
                            snapshotVersion, ErrorCode.INVALID_MODEL_OUTPUT.name(),
                            "Model output validation unavailable"),
                    principal, requestId, snapshotVersion, manifest, startTime);
        }

        boolean authorized;
        try {
            authorized = authorizationPort.authorizeExecution(
                    principal, manifest.metadata().id(), manifest.metadata().version());
        } catch (Exception e) {
            return terminalRecorder.record(new NaturalLanguageQueryUseCase.QueryResult(
                            NaturalLanguageQueryUseCase.QueryStatus.ERROR, null, null, null,
                            snapshotVersion, ErrorCode.PERMISSION_DENIED.name(),
                            "Authorization data source unavailable"),
                    principal, requestId, snapshotVersion, manifest, startTime);
        }
        if (!authorized) {
            return terminalRecorder.record(new NaturalLanguageQueryUseCase.QueryResult(
                            NaturalLanguageQueryUseCase.QueryStatus.ERROR, null, null, null,
                            snapshotVersion, ErrorCode.PERMISSION_DENIED.name(),
                            "Execution authorization denied"),
                    principal, requestId, snapshotVersion, manifest, startTime);
        }

        List<Object> resolvedArgs;
        try {
            SystemContext bindContext = new SystemContext(
                    requestId, System.currentTimeMillis() + 15000, null, locale);
            ArgumentBinder argumentBinder = new ArgumentBinder(
                    typeConverterRegistry, schemaValidator, principal, bindContext, manifest,
                    payloadLimits);
            resolvedArgs = argumentBinder.bind(select.arguments());
        } catch (Exception e) {
            return terminalRecorder.record(new NaturalLanguageQueryUseCase.QueryResult(
                            NaturalLanguageQueryUseCase.QueryStatus.ERROR, null, null, null,
                            snapshotVersion, ErrorCode.ARGUMENT_VALIDATION_FAILED.name(),
                            "Resolved arguments failed validation"),
                    principal, requestId, snapshotVersion, manifest, startTime);
        }

        String executionId = UUID.randomUUID().toString();
        ExecutionPlan plan = new ExecutionPlan(
                executionId,
                Sha256Digest.sha256Hex(principal.subject()),
                snapshotVersion,
                manifest.metadata().id(),
                manifest.metadata().version(),
                ManifestDigest.sha256(manifest),
                select.arguments(),
                resolvedArgs,
                "policy-" + executionId,
                manifest.spec().risk(),
                manifest.spec().resilience() != null
                        ? manifest.spec().resilience()
                        : new ResiliencePolicy(15000, 0, 10, true));

        if (manifest.spec().risk() == RiskLevel.WRITE_LOW
                || manifest.spec().risk() == RiskLevel.WRITE_HIGH) {
            Map<String, Object> planData = new HashMap<>();
            planData.put("executionId", executionId);
            planData.put("capabilityId", manifest.metadata().id());
            planData.put("capabilityVersion", manifest.metadata().version());
            planData.put("risk", manifest.spec().risk().name());
            planData.put("requiresConfirmation", true);
            planData.put("modelArguments", Map.copyOf(select.arguments()));
            return terminalRecorder.record(new NaturalLanguageQueryUseCase.QueryResult(
                            NaturalLanguageQueryUseCase.QueryStatus.COMPLETED, planData,
                            "Write operation requires confirmation: " + manifest.spec().displayName(),
                            null, snapshotVersion, null, null,
                            capabilityMetadata(manifest),
                            Map.of("id", executionId, "status", "PLAN_READY",
                                    "requiresConfirmation", true), null),
                    principal, requestId, snapshotVersion, manifest, startTime);
        }

        // 运行面自然语言链路的执行一律归属 gateway-nl 平面：诊断面是 dry-run，
        // 永远不会走到这里，因此此处平面是常量而非入参。
        DeterministicExecutionUseCase.ExecutionResult result =
                deterministicExecutionUseCase.execute(requestId, plan, principal, manifest,
                        AuditPlane.GATEWAY_NL);
        if (result.errorCode() != null) {
            return new NaturalLanguageQueryUseCase.QueryResult(
                    NaturalLanguageQueryUseCase.QueryStatus.ERROR, null, null, null,
                    snapshotVersion, result.errorCode(), result.summary());
        }
        return new NaturalLanguageQueryUseCase.QueryResult(
                NaturalLanguageQueryUseCase.QueryStatus.COMPLETED, result.data(),
                result.summary(), null, snapshotVersion, null, null,
                capabilityMetadata(manifest),
                Map.of("id", executionId, "status", "COMPLETED"), null);
    }

    private String safeLlmErrorMessage(ErrorCode errorCode) {
        return errorCode == ErrorCode.RATE_LIMITED
                ? "LLM provider rate limit reached"
                : "LLM routing unavailable";
    }

    private Map<String, Object> capabilityMetadata(CapabilityManifest manifest) {
        return Map.of("id", manifest.metadata().id(), "version", manifest.metadata().version());
    }

}
