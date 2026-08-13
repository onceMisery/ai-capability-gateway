package com.ai.gateway.bootstrap.config;

import com.ai.gateway.adapter.auth.satoken.SaTokenAuthConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * 认证/授权 Provider 装配（可插拔）。
 *
 * <p>聚合 stub 与 sa-token 两套认证实现：</p>
 * <ul>
 * <li>{@link StubAuthConfiguration}：默认激活（{@code gateway.auth.provider}
 * 未设置或为 {@code stub}），提供初始发布的降级认证/授权；</li>
 * <li>{@link SaTokenAuthConfiguration}：在
 * {@code gateway.auth.provider=sa-token} 时激活，提供 JWT 认证与基于 ACL
 * 的授权；</li>
 * <li>{@link SaTokenRedisDaoConfiguration}：仅在 sa-token + redis 组合下
 * 激活，将会话存储挂载到共享 Redis。</li>
 * </ul>
 *
 * <p>两个 Provider 恰好有一个提供 AuthenticationPort/AuthorizationPort
 * Bean，具体由各自的条件装配注解控制，主启动类无需关心。</p>
 *
 * @since 0.1.0
 */
@Configuration
@Import({
        StubAuthConfiguration.class,
        SaTokenAuthConfiguration.class,
        SaTokenRedisDaoConfiguration.class,
})
public class AuthProviderConfiguration {
}
