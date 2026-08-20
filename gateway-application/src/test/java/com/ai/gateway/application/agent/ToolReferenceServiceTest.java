package com.ai.gateway.application.agent;

import com.ai.gateway.application.catalog.ActiveCatalogView;
import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.Principal;
import com.ai.gateway.domain.model.RiskLevel;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class ToolReferenceServiceTest {

    private static final byte[] CURRENT_KEY =
            "0123456789abcdef0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final byte[] NEXT_KEY =
            "abcdef0123456789abcdef0123456789".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final Instant NOW = Instant.parse("2026-08-19T10:00:00Z");

    @Test
    void opaqueReferenceVerifiesWithoutExposingBindingOrPrincipal() {
        CapabilityManifest manifest = AgentTestFixtures.manifest(
                "orders.detail.query", RiskLevel.READ_ONLY, "Query one order");
        ActiveCatalogView view = ActiveCatalogView.from(AgentTestFixtures.snapshot(8L, manifest));
        Principal principal = AgentTestFixtures.principal("user-sensitive");
        ToolReferenceService service = service("k1", CURRENT_KEY, null, null, NOW);

        ToolReferenceService.IssuedReference issued = service.issue(principal, manifest, 8L, 42L);
        ToolReferenceService.Verification verified = service.verify(
                issued.toolRef(), principal, view, 42L);

        assertThat(verified.valid()).isTrue();
        assertThat(verified.manifest()).isSameAs(manifest);
        assertThat(issued.toolRef())
                .doesNotContain("orders.detail.query")
                .doesNotContain("1.0.0")
                .doesNotContain("user-sensitive");
    }

    @Test
    void tamperPrincipalPolicyCatalogAndExpiryFailClosed() {
        CapabilityManifest manifest = AgentTestFixtures.manifest(
                "orders.detail.query", RiskLevel.READ_ONLY, "Query one order");
        ActiveCatalogView view8 = ActiveCatalogView.from(AgentTestFixtures.snapshot(8L, manifest));
        ActiveCatalogView view9 = ActiveCatalogView.from(AgentTestFixtures.snapshot(9L, manifest));
        Principal principal = AgentTestFixtures.principal("user-1");
        ToolReferenceService issuer = service("k1", CURRENT_KEY, null, null, NOW);
        String toolRef = issuer.issue(principal, manifest, 8L, 42L).toolRef();

        String tampered = toolRef.substring(0, toolRef.length() - 1)
                + (toolRef.endsWith("A") ? "B" : "A");
        assertThat(issuer.verify(tampered, principal, view8, 42L).failure())
                .isEqualTo(ToolReferenceService.Failure.SIGNATURE_INVALID);
        assertThat(issuer.verify(toolRef, AgentTestFixtures.principal("user-2"), view8, 42L)
                .failure()).isEqualTo(ToolReferenceService.Failure.PRINCIPAL_MISMATCH);
        assertThat(issuer.verify(toolRef, principal, view8, 43L).failure())
                .isEqualTo(ToolReferenceService.Failure.POLICY_CHANGED);
        assertThat(issuer.verify(toolRef, principal, view9, 42L).failure())
                .isEqualTo(ToolReferenceService.Failure.CATALOG_CHANGED);

        ToolReferenceService later = service(
                "k1", CURRENT_KEY, null, null, NOW.plusSeconds(121));
        assertThat(later.verify(toolRef, principal, view8, 42L).failure())
                .isEqualTo(ToolReferenceService.Failure.EXPIRED);
    }

    @Test
    void previousKeyRemainsValidDuringRotationWindow() {
        CapabilityManifest manifest = AgentTestFixtures.manifest(
                "orders.detail.query", RiskLevel.READ_ONLY, "Query one order");
        ActiveCatalogView view = ActiveCatalogView.from(AgentTestFixtures.snapshot(8L, manifest));
        Principal principal = AgentTestFixtures.principal("user-1");
        String oldReference = service("old", CURRENT_KEY, null, null, NOW)
                .issue(principal, manifest, 8L, 42L).toolRef();

        ToolReferenceService rotated = service(
                "new", NEXT_KEY, "old", CURRENT_KEY, NOW.plusSeconds(1));

        assertThat(rotated.verify(oldReference, principal, view, 42L).valid()).isTrue();
    }

    private static ToolReferenceService service(
            String currentId, byte[] current,
            String previousId, byte[] previous,
            Instant now) {
        return new ToolReferenceService(
                currentId, current, previousId, previous, 120L,
                Clock.fixed(now, ZoneOffset.UTC), new SecureRandom(new byte[]{1, 2, 3}));
    }
}
