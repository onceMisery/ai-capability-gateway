package com.ai.gateway.adapter.web.filter;

import com.ai.gateway.adapter.web.support.RequestContextFactory;
import com.ai.gateway.domain.model.AdminAction;
import com.ai.gateway.domain.model.Principal;
import com.ai.gateway.domain.port.AuthenticationPort;
import com.ai.gateway.domain.port.AuthorizationPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;

/**
 * 对每个管理 API 强制执行身份认证与管理员授权。
 *
 * <p>在请求进入控制器之前校验调用方身份与权限，未通过则直接返回错误信封。</p>
 *
 * @author cmiracle@163.com
 * @since 0.1.0
 */
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class AdminAuthenticationFilter extends OncePerRequestFilter {

    public static final String PRINCIPAL_ATTRIBUTE =
            AdminAuthenticationFilter.class.getName() + ".principal";

    private final AuthenticationPort authenticationPort;
    private final AuthorizationPort authorizationPort;
    private final RequestContextFactory requestContextFactory;
    private final ObjectMapper objectMapper;

    public AdminAuthenticationFilter(AuthenticationPort authenticationPort,
                                     AuthorizationPort authorizationPort,
                                     RequestContextFactory requestContextFactory,
                                     ObjectMapper objectMapper) {
        this.authenticationPort = Objects.requireNonNull(authenticationPort);
        this.authorizationPort = Objects.requireNonNull(authorizationPort);
        this.requestContextFactory = Objects.requireNonNull(requestContextFactory);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (!path.startsWith("/admin/v1/")) {
            return true;
        }
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        return path.equals("/admin/v1/console/auth/login")
                || path.equals("/admin/v1/console/auth/refresh")
                || path.equals("/admin/v1/console/auth/capabilities");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        Principal principal;
        try {
            principal = authenticationPort.authenticate(requestContextFactory.from(request));
        } catch (Exception e) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "AUTHENTICATION_FAILED", "登录凭证无效或缺失，请重新登录。");
            return;
        }
        AdminAction action = actionFor(request);
        if (!authorizationPort.authorizeAdmin(principal, action)) {
            writeError(response, HttpServletResponse.SC_FORBIDDEN,
                    "PERMISSION_DENIED", "当前账号没有执行此管理操作的权限。");
            return;
        }
        request.setAttribute(PRINCIPAL_ATTRIBUTE, principal);
        filterChain.doFilter(request, response);
    }

    private AdminAction actionFor(HttpServletRequest request) {
        if ("GET".equalsIgnoreCase(request.getMethod())) {
            return AdminAction.READ;
        }
        String path = request.getRequestURI();
        if (path.contains("/acl/") || path.contains("/roles") || path.contains("/permissions")) {
            return AdminAction.MANAGE_ACL;
        }
        if (path.contains("/ratelimit/") || path.contains("/config")) {
            return AdminAction.CONFIGURE;
        }
        if (path.contains("releases:publish")) return AdminAction.PUBLISH;
        if (path.contains("releases:rollback")) return AdminAction.ROLLBACK;
        if (path.contains(":suspend")) return AdminAction.SUSPEND;
        if (path.contains(":approve") || path.contains(":validate")) return AdminAction.APPROVE;
        return AdminAction.IMPORT;
    }

    private void writeError(HttpServletResponse response, int status,
                            String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), Map.of(
                "status", "ERROR",
                "error", Map.of("errorCode", code, "message", message)));
    }
}
