package com.ai.gateway.adapter.web.support;

import com.ai.gateway.domain.model.AdminAction;
import com.ai.gateway.domain.model.Principal;
import com.ai.gateway.domain.model.RequestContext;
import com.ai.gateway.domain.port.AuthenticationPort;
import com.ai.gateway.domain.port.AuthorizationPort;
import jakarta.servlet.http.HttpServletRequest;
import com.ai.gateway.adapter.web.filter.AdminAuthenticationFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.RequestAttributes;

import java.util.Map;

/**
 * 管理安全管理器，提供 Principal 提取和 authorizeAdmin 门禁检查
 *
 * @since 0.1.0
 */
public final class SecurityHelper {

    private static final Logger log = LoggerFactory.getLogger(SecurityHelper.class);

    private SecurityHelper() {
        // utility class
    }

    /**
     * 从当前请求中提取已认证的 Principal
     *
     * @param authenticationPort 认证端口
     * @return 已认证的 Principal，或 null（未认证 / 提取失败）
     */
    public static Principal getCurrentPrincipal(AuthenticationPort authenticationPort) {
        try {
            RequestAttributes attrs = RequestContextHolder.currentRequestAttributes();
            HttpServletRequest request = (HttpServletRequest) attrs.resolveReference(RequestAttributes.REFERENCE_REQUEST);
            if (request == null) {
                return null;
            }
            Object existing = request.getAttribute(AdminAuthenticationFilter.PRINCIPAL_ATTRIBUTE);
            if (existing instanceof Principal principal) {
                return principal;
            }
            RequestContextFactory factory = new RequestContextFactory();
            RequestContext context = factory.from(request);
            return authenticationPort.authenticate(context);
        } catch (Exception e) {
            log.debug("Could not extract principal from request: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 检查当前请求是否拥有管理操作权限，若不通过则抛出 SecurityException
     *
     * @param authenticationPort 认证端口
     * @param authorizationPort  授权端口
     * @param action             管理操作类型
     * @throws SecurityException 如果未授权
     */
    public static void requireAdmin(AuthenticationPort authenticationPort,
                                     AuthorizationPort authorizationPort,
                                     AdminAction action) {
        Principal principal = getCurrentPrincipal(authenticationPort);
        if (principal == null) {
            throw new SecurityException("AUTHENTICATION_FAILED: no valid principal");
        }
        if (!authorizationPort.authorizeAdmin(principal, action)) {
            throw new SecurityException("PERMISSION_DENIED: admin role required for " + action);
        }
    }
}
