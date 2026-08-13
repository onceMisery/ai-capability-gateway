package com.ai.gateway.adapter.web.filter;

import com.ai.gateway.adapter.web.support.RequestContextFactory;
import com.ai.gateway.domain.model.AdminAction;
import com.ai.gateway.domain.model.Principal;
import com.ai.gateway.domain.port.AuthenticationPort;
import com.ai.gateway.domain.port.AuthorizationPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminAuthenticationFilterTest {

    private AuthenticationPort authenticationPort;
    private AuthorizationPort authorizationPort;
    private AdminAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        authenticationPort = mock(AuthenticationPort.class);
        authorizationPort = mock(AuthorizationPort.class);
        filter = new AdminAuthenticationFilter(authenticationPort, authorizationPort,
                new RequestContextFactory(), new ObjectMapper());
    }

    @Test
    void anonymousAdminReadReturnsUnauthorizedEnvelope() throws Exception {
        when(authenticationPort.authenticate(any()))
                .thenThrow(new SecurityException("missing"));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/v1/capabilities");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("AUTHENTICATION_FAILED");
    }

    @Test
    void nonAdminCallerReturnsForbiddenEnvelope() throws Exception {
        Principal principal = principal("user");
        when(authenticationPort.authenticate(any())).thenReturn(principal);
        when(authorizationPort.authorizeAdmin(principal, AdminAction.READ)).thenReturn(false);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/v1/capabilities");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("PERMISSION_DENIED");
    }

    @Test
    void authorizedAdminReadContinuesWithTrustedPrincipalAttribute() throws Exception {
        Principal principal = principal("admin");
        when(authenticationPort.authenticate(any())).thenReturn(principal);
        when(authorizationPort.authorizeAdmin(principal, AdminAction.READ)).thenReturn(true);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/v1/capabilities");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(request.getAttribute(AdminAuthenticationFilter.PRINCIPAL_ATTRIBUTE))
                .isSameAs(principal);
        verify(authorizationPort).authorizeAdmin(principal, AdminAction.READ);
    }

    private static Principal principal(String subject) {
        return new Principal(subject, 1L, List.of("admin"), List.of("*"),
                Instant.now(), "TEST");
    }
}
