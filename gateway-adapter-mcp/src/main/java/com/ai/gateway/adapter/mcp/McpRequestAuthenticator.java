package com.ai.gateway.adapter.mcp;

import com.ai.gateway.domain.model.Principal;
import com.ai.gateway.domain.model.RequestContext;
import com.ai.gateway.domain.port.AuthenticationPort;

import java.util.Objects;

/**
 * MCP 传输请求的认证策略。
 *
 * <p>生产模式使用 Bearer 认证；本地开发可注入固定开发主体。业务授权仍由网关
 * 的确定性授权链负责，本策略只决定传输层请求对应的主体。</p>
 */
@FunctionalInterface
public interface McpRequestAuthenticator {

    /**
     * 返回请求主体；认证失败时返回 {@code null}。
     */
    Principal authenticate(RequestContext context);

    /**
     * 创建仅接受 Authorization Bearer 头的认证策略。
     */
    static McpRequestAuthenticator bearer(AuthenticationPort authenticationPort) {
        Objects.requireNonNull(authenticationPort, "authenticationPort must not be null");
        return context -> {
            String authorization = context.header("Authorization");
            if (authorization == null
                    || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)
                    || authorization.substring(7).isBlank()) {
                return null;
            }
            try {
                return authenticationPort.authenticate(context);
            } catch (RuntimeException e) {
                return null;
            }
        };
    }

    /**
     * 创建固定返回本地开发主体的认证策略。
     */
    static McpRequestAuthenticator noAuth(Principal developmentPrincipal) {
        Principal principal = Objects.requireNonNull(developmentPrincipal,
                "developmentPrincipal must not be null");
        return ignored -> principal;
    }
}
