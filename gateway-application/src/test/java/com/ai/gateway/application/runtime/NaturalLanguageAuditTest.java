package com.ai.gateway.application.runtime;

import com.ai.gateway.domain.model.AuditEvent;
import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.CatalogSnapshot;
import com.ai.gateway.domain.model.ErrorCode;
import com.ai.gateway.domain.model.ModelDecision;
import com.ai.gateway.domain.model.Principal;
import com.ai.gateway.domain.model.RequestContext;
import com.ai.gateway.domain.model.RiskLevel;
import com.ai.gateway.domain.model.ValidationReport;
import com.ai.gateway.domain.port.*;
import com.ai.gateway.domain.service.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class NaturalLanguageAuditTest {

    @Test
    void noSnapshotRecordsTerminalEventWithOriginalRequestId() {
        AuthenticationPort authentication = mock(AuthenticationPort.class);
        CatalogPort catalog = mock(CatalogPort.class);
        AuditPort audit = mock(AuditPort.class);
        Principal principal = new Principal("user-1", 7L, List.of("user"), List.of(),
                Instant.now(), "JWT");
        when(authentication.authenticate(any())).thenReturn(principal);
        when(catalog.loadCurrentSnapshot("production")).thenReturn(null);

        NaturalLanguageQueryUseCase useCase = new NaturalLanguageQueryUseCase(
                authentication, mock(AuthorizationPort.class), catalog,
                mock(CandidateRetriever.class), mock(LlmRouterPort.class),
                mock(SchemaValidator.class), new AliasGenerator(), mock(TypeConverterRegistry.class),
                new RedactionService(), audit, new ThresholdEvaluator(),
                new DeadlineBudgetManager(), new TextNormalizer(),
                mock(InteractionRepository.class), mock(DeterministicExecutionUseCase.class),
                "production");

        useCase.execute(RequestContext.empty(), "req-1", "query", "zh-CN", "UTC");

        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).recordEvent(event.capture());
        assertThat(event.getValue().requestId()).isEqualTo("req-1");
        assertThat(event.getValue().resultCode()).isEqualTo("NO_CAPABILITY_MATCH");
    }

    @Test
    void executionAuthorizationFailureReturnsStableErrorAndRecordsTerminalEvent() {
        RoutingFixture fixture = new RoutingFixture();
        when(fixture.authorization.authorizeExecution(any(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("database host leaked"));

        NaturalLanguageQueryUseCase.QueryResult result = fixture.useCase().execute(
                RequestContext.empty(), "req-auth", "query", "zh-CN", "UTC");

        assertThat(result.errorCode()).isEqualTo(ErrorCode.PERMISSION_DENIED.name());
        assertThat(result.errorMessage()).doesNotContain("database host leaked");
        assertTerminal(fixture.audit, "req-auth", ErrorCode.PERMISSION_DENIED.name());
    }

    @Test
    void argumentBindingFailureReturnsStableErrorAndRecordsTerminalEvent() {
        RoutingFixture fixture = new RoutingFixture();
        NaturalLanguageQueryUseCase useCase = fixture.useCase();
        when(fixture.schemaValidator.validate(anyMap(), anyMap()))
                .thenReturn(ValidationReport.success())
                .thenReturn(ValidationReport.failure(List.of("internal schema detail")));
        when(fixture.authorization.authorizeExecution(any(), anyString(), anyString()))
                .thenReturn(true);

        NaturalLanguageQueryUseCase.QueryResult result = useCase.execute(
                RequestContext.empty(), "req-bind", "query", "zh-CN", "UTC");

        assertThat(result.errorCode()).isEqualTo(ErrorCode.ARGUMENT_VALIDATION_FAILED.name());
        assertThat(result.errorMessage()).doesNotContain("internal schema detail");
        assertTerminal(fixture.audit, "req-bind", ErrorCode.ARGUMENT_VALIDATION_FAILED.name());
    }

    @Test
    void clarificationPersistenceFailureReturnsStableErrorAndRecordsTerminalEvent() {
        RoutingFixture fixture = new RoutingFixture();
        fixture.clarificationRequired = true;
        doThrow(new IllegalStateException("redis address leaked"))
                .when(fixture.interactions).save(any());

        NaturalLanguageQueryUseCase.QueryResult result = fixture.useCase().execute(
                RequestContext.empty(), "req-clarify", "query", "zh-CN", "UTC");

        assertThat(result.errorCode()).isEqualTo(ErrorCode.PROTOCOL_ERROR.name());
        assertThat(result.errorMessage()).doesNotContain("redis address leaked");
        assertTerminal(fixture.audit, "req-clarify", ErrorCode.PROTOCOL_ERROR.name());
    }

    @Test
    void llmFailureDoesNotExposeProviderMessage() {
        RoutingFixture fixture = new RoutingFixture();
        NaturalLanguageQueryUseCase useCase = fixture.useCase();
        when(fixture.llm.route(anyString(), anyList()))
                .thenThrow(new LlmRouterPort.LlmRoutingException(
                        ErrorCode.LLM_UNAVAILABLE, "provider response contained a secret"));

        NaturalLanguageQueryUseCase.QueryResult result = useCase.execute(
                RequestContext.empty(), "req-llm", "query", "zh-CN", "UTC");

        assertThat(result.errorCode()).isEqualTo(ErrorCode.LLM_UNAVAILABLE.name());
        assertThat(result.errorMessage()).doesNotContain("provider response contained a secret");
        assertTerminal(fixture.audit, "req-llm", ErrorCode.LLM_UNAVAILABLE.name());
    }

    @Test
    void schemaValidatorFailureReturnsStableErrorAndRecordsTerminalEvent() {
        RoutingFixture fixture = new RoutingFixture();
        NaturalLanguageQueryUseCase useCase = fixture.useCase();
        when(fixture.schemaValidator.validate(anyMap(), anyMap()))
                .thenThrow(new IllegalStateException("schema engine secret leaked"));

        NaturalLanguageQueryUseCase.QueryResult result = useCase.execute(
                RequestContext.empty(), "req-schema", "query", "zh-CN", "UTC");

        assertThat(result.errorCode()).isEqualTo(ErrorCode.INVALID_MODEL_OUTPUT.name());
        assertThat(result.errorMessage()).doesNotContain("schema engine secret leaked");
        assertTerminal(fixture.audit, "req-schema", ErrorCode.INVALID_MODEL_OUTPUT.name());
    }

    private static void assertTerminal(AuditPort audit, String requestId, String resultCode) {
        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).recordEvent(event.capture());
        assertThat(event.getValue().requestId()).isEqualTo(requestId);
        assertThat(event.getValue().resultCode()).isEqualTo(resultCode);
    }

    private static final class RoutingFixture {
        private final AuthenticationPort authentication = mock(AuthenticationPort.class);
        private final AuthorizationPort authorization = mock(AuthorizationPort.class);
        private final CatalogPort catalog = mock(CatalogPort.class);
        private final CandidateRetriever retriever = mock(CandidateRetriever.class);
        private final LlmRouterPort llm = mock(LlmRouterPort.class);
        private final SchemaValidator schemaValidator = mock(SchemaValidator.class);
        private final AliasGenerator aliases = mock(AliasGenerator.class);
        private final AuditPort audit = mock(AuditPort.class);
        private final InteractionRepository interactions = mock(InteractionRepository.class);
        private final CapabilityManifest manifest = mock(CapabilityManifest.class, RETURNS_DEEP_STUBS);
        private boolean clarificationRequired;

        private NaturalLanguageQueryUseCase useCase() {
            Principal principal = new Principal("user-1", 7L, List.of("user"), List.of(),
                    Instant.now(), "JWT");
            Map<String, Object> schema = Map.of("type", "object");
            when(authentication.authenticate(any())).thenReturn(principal);
            when(manifest.metadata().id()).thenReturn("order.query");
            when(manifest.metadata().version()).thenReturn("1.0.0");
            when(manifest.spec().risk()).thenReturn(RiskLevel.READ_ONLY);
            when(manifest.spec().displayName()).thenReturn("Order query");
            when(manifest.spec().description()).thenReturn("Query an order");
            when(manifest.spec().examples().positive()).thenReturn(List.of("find order"));
            when(manifest.spec().examples().negative()).thenReturn(List.of());
            when(manifest.spec().examples().synonyms()).thenReturn(List.of("order"));
            when(manifest.spec().inputSchema()).thenReturn(schema);
            when(catalog.loadCurrentSnapshot("production"))
                    .thenReturn(new CatalogSnapshot(3L, "production", List.of(manifest),
                            "policy-1", "digest"));
            when(authorization.filterVisibleCapabilities(principal, List.of(manifest)))
                    .thenReturn(List.of(manifest));
            CandidateRetriever.ScoredCapability candidate =
                    new CandidateRetriever.ScoredCapability(manifest, 2.0);
            when(retriever.retrieve(anyString(), anyList(), anyInt()))
                    .thenReturn(clarificationRequired
                            ? List.of(candidate,
                                    new CandidateRetriever.ScoredCapability(manifest, 1.8))
                            : List.of(candidate));
            when(aliases.generate(3L, "order.query", "1.0.0")).thenReturn("cap_order");
            when(llm.route(anyString(), anyList()))
                    .thenReturn(new ModelDecision.SelectDecision("cap_order", Map.of()));
            when(schemaValidator.validate(anyMap(), anyMap()))
                    .thenReturn(ValidationReport.success());

            return new NaturalLanguageQueryUseCase(
                    authentication, authorization, catalog, retriever, llm,
                    schemaValidator, aliases, mock(TypeConverterRegistry.class),
                    new RedactionService(), audit, new ThresholdEvaluator(),
                    new DeadlineBudgetManager(), new TextNormalizer(), interactions,
                    mock(DeterministicExecutionUseCase.class), "production");
        }
    }
}
