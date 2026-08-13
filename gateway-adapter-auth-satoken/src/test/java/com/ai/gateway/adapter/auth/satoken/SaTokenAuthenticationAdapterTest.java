package com.ai.gateway.adapter.auth.satoken;

import com.ai.gateway.domain.model.Principal;
import com.ai.gateway.domain.model.RequestContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link SaTokenAuthenticationAdapter}.
 *
 * <p>Verifies the JWT round-trip (issue → verify → Principal mapping),
 * multi-source token resolution, and Fail-Closed behavior on invalid or
 * missing credentials.</p>
 */
class SaTokenAuthenticationAdapterTest {

    private static final String SECRET = "test-secret-key-for-sa-token-jwt";

    private SaTokenAuthenticationAdapter adapter;

    @BeforeEach
    void setUp() {
        SaTokenAuthProperties properties = new SaTokenAuthProperties();
        properties.setJwtSecretKey(SECRET);
        adapter = new SaTokenAuthenticationAdapter(properties);
    }

    @Test
    @DisplayName("issue then validate maps JWT claims to Principal")
    void roundTripMapsClaimsToPrincipal() {
        String token = adapter.issueToken("user-123", Map.of(
                "orgId", 42L,
                "roles", List.of("user", "analyst"),
                "permissions", List.of("order.query")));

        Principal principal = adapter.validateToken(token);

        assertThat(principal.subject()).isEqualTo("user-123");
        assertThat(principal.orgId()).isEqualTo(42L);
        assertThat(principal.roles()).containsExactlyInAnyOrder("user", "analyst");
        assertThat(principal.permissions()).containsExactly("order.query");
        assertThat(principal.authMethod()).isEqualTo("SA_TOKEN_JWT");
    }

    @Test
    @DisplayName("authenticate resolves Bearer token from Authorization header")
    void authenticateResolvesBearerHeader() {
        String token = adapter.issueToken("header-user", Map.of("orgId", 1L));
        RequestContext context = new RequestContext(
                Map.of("Authorization", "Bearer " + token), Map.of(), Map.of(), "127.0.0.1");

        Principal principal = adapter.authenticate(context);

        assertThat(principal.subject()).isEqualTo("header-user");
    }

    @Test
    @DisplayName("authenticate resolves token from cookie when no header present")
    void authenticateResolvesCookie() {
        String token = adapter.issueToken("cookie-user", null);
        RequestContext context = new RequestContext(
                Map.of(), Map.of("Authorization", token), Map.of(), "127.0.0.1");

        Principal principal = adapter.authenticate(context);

        assertThat(principal.subject()).isEqualTo("cookie-user");
    }

    @Test
    @DisplayName("validateToken fails closed on tampered token")
    void validateTokenFailsOnTamperedToken() {
        String token = adapter.issueToken("user-1", null);
        String tampered = token.substring(0, token.length() - 2) + "xx";

        assertThatThrownBy(() -> adapter.validateToken(tampered))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("AUTHENTICATION_FAILED");
    }

    @Test
    @DisplayName("validateToken fails closed on wrong secret")
    void validateTokenFailsOnWrongSecret() {
        String token = adapter.issueToken("user-1", null);

        SaTokenAuthProperties other = new SaTokenAuthProperties();
        other.setJwtSecretKey("a-different-secret");
        SaTokenAuthenticationAdapter otherAdapter = new SaTokenAuthenticationAdapter(other);

        assertThatThrownBy(() -> otherAdapter.validateToken(token))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("AUTHENTICATION_FAILED");
    }

    @Test
    @DisplayName("authenticate fails closed when no credential present")
    void authenticateFailsWhenNoCredential() {
        assertThatThrownBy(() -> adapter.authenticate(RequestContext.empty()))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("AUTHENTICATION_FAILED");
    }

    @Test
    @DisplayName("constructor rejects missing secret key")
    void constructorRejectsMissingSecret() {
        assertThatThrownBy(() -> new SaTokenAuthenticationAdapter(new SaTokenAuthProperties()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void tokenIssuerExposesRefreshAndRevocationLifecycle() throws Exception {
        Class<?> issuer = com.ai.gateway.domain.port.TokenIssuerPort.class;
        assertThat(issuer.getMethod("issueTokenPair", String.class, Map.class)).isNotNull();
        assertThat(issuer.getMethod("refresh", String.class)).isNotNull();
        assertThat(issuer.getMethod("revokeToken", String.class)).isNotNull();
    }

    @Test
    void accessTokenExpiresAtConfiguredDeadline() throws Exception {
        SaTokenAuthProperties properties = new SaTokenAuthProperties();
        properties.setJwtSecretKey(SECRET);
        properties.setAccessTokenTimeoutSeconds(1L);
        SaTokenAuthenticationAdapter shortLivedAdapter =
                new SaTokenAuthenticationAdapter(properties);
        String token = shortLivedAdapter.issueTokenPair("user-1", Map.of()).accessToken();

        Thread.sleep(1200L);

        assertThatThrownBy(() -> shortLivedAdapter.validateToken(token))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("AUTHENTICATION_FAILED");
    }

    @Test
    void refreshRotatesSessionAndInvalidatesOldTokens() {
        var oldPair = adapter.issueTokenPair("user-1", Map.of("orgId", 7L));

        var newPair = adapter.refresh(oldPair.refreshToken());

        assertThat(adapter.validateToken(newPair.accessToken()).subject()).isEqualTo("user-1");
        assertThatThrownBy(() -> adapter.validateToken(oldPair.accessToken()))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> adapter.refresh(oldPair.refreshToken()))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void revokeInvalidatesWholeSession() {
        var pair = adapter.issueTokenPair("user-1", Map.of());

        adapter.revokeToken(pair.accessToken());

        assertThatThrownBy(() -> adapter.validateToken(pair.accessToken()))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> adapter.refresh(pair.refreshToken()))
                .isInstanceOf(SecurityException.class);
    }
}
