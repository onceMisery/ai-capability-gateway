package com.ai.gateway.application.agent;

import com.ai.gateway.application.catalog.InMemoryCatalogManager;
import com.ai.gateway.application.runtime.AgentToolCallUseCase;
import com.ai.gateway.domain.model.AuditPlane;
import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.CatalogSnapshot;
import com.ai.gateway.domain.model.Principal;
import com.ai.gateway.domain.model.PolicySnapshot;
import com.ai.gateway.domain.model.CapabilityVisibility;
import com.ai.gateway.domain.model.RequestContext;
import com.ai.gateway.domain.model.RiskLevel;
import com.ai.gateway.domain.port.AuthenticationPort;
import com.ai.gateway.domain.port.AuthorizationPort;
import com.ai.gateway.domain.port.CatalogPort;
import com.ai.gateway.domain.port.TelemetryPort;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentHostToolCallUseCaseTest {

    @Test
    void verifiesReferenceAndDispatchesPinnedManifestWithoutLegacyLookup() {
        Fixtures fixtures = new Fixtures();
        when(fixtures.delegate.callResolved(
                anyString(), eq(fixtures.principal), any(), eq(fixtures.manifest),
                anyMap(), eq("zh-CN"), eq("idem-1"), anyBoolean(),
                eq(AuditPlane.AGENT_HOST)))
                .thenReturn(new AgentToolCallUseCase.Result(
                        AgentToolCallUseCase.Status.COMPLETED, Map.of("ok", true),
                        null, "completed", 8L, "orders.detail.query", "1.0.0",
                        null, null, null));

        AgentHostToolCallUseCase.Result result = fixtures.useCase.call(
                RequestContext.empty(), "req-1", fixtures.toolRef,
                Map.of("orderNo", "SO-1"), "zh-CN", "idem-1");

        assertThat(result.status()).isEqualTo(AgentHostToolCallUseCase.Status.COMPLETED);
        assertThat(result.data()).containsEntry("ok", true);
        assertThat(result.catalogVersion()).isEqualTo(8L);
        assertThat(result.policyEpoch()).isEqualTo(42L);
        verify(fixtures.delegate, never()).call(
                any(), anyString(), anyString(), anyString(), anyMap(),
                anyString(), any(Long.class), anyString());
    }

    @Test
    void policyChangeInvalidatesReferenceBeforeDispatch() {
        Fixtures fixtures = new Fixtures();
        when(fixtures.authorization.resolvePolicySnapshot(fixtures.principal)).thenReturn(
                PolicySnapshot.from(CapabilityVisibility.all(43L)));

        AgentHostToolCallUseCase.Result result = fixtures.useCase.call(
                RequestContext.empty(), "req-1", fixtures.toolRef,
                Map.of("orderNo", "SO-1"), "zh-CN", "idem-1");

        assertThat(result.status()).isEqualTo(AgentHostToolCallUseCase.Status.ERROR);
        assertThat(result.errorCode()).isEqualTo("POLICY_CHANGED");
        verify(fixtures.delegate, never()).callResolved(
                anyString(), any(), any(), any(), anyMap(), anyString(), anyString());
    }

    private static final class Fixtures {
        private final AuthenticationPort authentication = mock(AuthenticationPort.class);
        private final AuthorizationPort authorization = mock(AuthorizationPort.class);
        private final CatalogPort catalog = mock(CatalogPort.class);
        private final AgentToolCallUseCase delegate = mock(AgentToolCallUseCase.class);
        private final Principal principal = AgentTestFixtures.principal("user-1");
        private final CapabilityManifest manifest = AgentTestFixtures.manifest(
                "orders.detail.query", RiskLevel.READ_ONLY, "Query one order");
        private final CatalogSnapshot snapshot = AgentTestFixtures.snapshot(8L, manifest);
        private final ToolReferenceService references = new ToolReferenceService(
                "k1", "0123456789abcdef0123456789abcdef"
                .getBytes(StandardCharsets.UTF_8), null, null, 120L);
        private final InMemoryCatalogManager manager = new InMemoryCatalogManager(catalog);
        private final String toolRef;
        private final AgentHostToolCallUseCase useCase;

        private Fixtures() {
            when(catalog.loadCurrentSnapshot("production")).thenReturn(snapshot);
            assertThat(manager.loadAndActivate("production")).isTrue();
            when(authentication.authenticate(any())).thenReturn(principal);
            when(authorization.resolvePolicySnapshot(principal)).thenReturn(
                    PolicySnapshot.from(CapabilityVisibility.all(42L)));
            toolRef = references.issue(principal, manifest, 8L, 42L).toolRef();
            useCase = new AgentHostToolCallUseCase(
                    authentication, authorization, manager, references, delegate,
                    mock(TelemetryPort.class));
        }
    }
}
