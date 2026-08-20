package com.ai.gateway.adapter.auth.satoken;

import cn.dev33.satoken.config.SaTokenConfig;
import com.ai.gateway.domain.port.AuthenticationPort;
import com.ai.gateway.domain.port.AuthorizationPort;
import com.ai.gateway.domain.port.AclRepository;
import com.ai.gateway.domain.port.TokenIssuerPort;
import com.ai.gateway.domain.port.TelemetryPort;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 基于 Sa-Token 的认证/授权适配器的条件式 Spring 装配。
 *
 * <p>仅在 {@code gateway.auth.provider=sa-token} 时激活。这是技术选型文档中描述
 * 的可插拔接缝：更换认证提供方只需修改配置并替换不同的
 * {@link AuthenticationPort}/{@link AuthorizationPort} Bean——领域端口保持
 * 不变。</p>
 *
 * <p>Sa-Token 的 JWT 密钥保存在 {@link SaTokenAuthProperties} 中，由适配器显式
 * 传递给 Sa-Token 的 JWT 工具类。</p>
 *
 * @author cmiracle@163.com
 * @since 0.1.0
 */
@Configuration
@ConditionalOnProperty(name = "gateway.auth.provider", havingValue = "sa-token")
public class SaTokenAuthConfiguration {

    /**
     * 使用 Spring 的 {@link Environment} 绑定 {@code gateway.auth.sa-token.*} 属性。
     *
     * <p>采用编程式绑定可使本模块不依赖 Spring Boot 的
     * {@code @ConfigurationProperties} 机制，与避免框架层面侵入的技术选型决策保持一致。</p>
     *
     * @param environment Spring 环境
     * @return 填充完成后的属性持有对象
     */
    @Bean
    public SaTokenAuthProperties saTokenAuthProperties(Environment environment) {
        SaTokenAuthProperties properties = new SaTokenAuthProperties();
        properties.setTokenName(
                environment.getProperty("gateway.auth.sa-token.token-name",
                        SaTokenAuthProperties.DEFAULT_TOKEN_NAME));
        properties.setLoginType(
                environment.getProperty("gateway.auth.sa-token.login-type",
                        SaTokenAuthProperties.DEFAULT_LOGIN_TYPE));
        properties.setJwtSecretKey(
                environment.getProperty("gateway.auth.sa-token.jwt-secret-key"));
        properties.setAccessTokenTimeoutSeconds(
                environment.getProperty("gateway.auth.sa-token.access-token-timeout-seconds",
                        Long.class, SaTokenAuthProperties.DEFAULT_ACCESS_TOKEN_TIMEOUT_SECONDS));
        properties.setRefreshTokenTimeoutSeconds(
                environment.getProperty("gateway.auth.sa-token.refresh-token-timeout-seconds",
                        Long.class, SaTokenAuthProperties.DEFAULT_REFRESH_TOKEN_TIMEOUT_SECONDS));
        return properties;
    }

    /**
     * 发布一个携带共享 JWT 密钥和令牌名称的 Sa-Token {@link SaTokenConfig}。
     *
     * <p>适配器在每次调用时都会将密钥显式传递给 Sa-Token 的 JWT 工具类；该 Bean
     * 额外将这些值以 Sa-Token 配置对象的形式暴露出来，用于将来与会话存储
     * （{@code sa-token-dao-redisson}，里程碑 M2）集成。</p>
     *
     * @param properties 已绑定的 Sa-Token 属性
     * @return Sa-Token 配置
     */
    @Bean
    public SaTokenConfig saTokenConfig(SaTokenAuthProperties properties) {
        properties.validate();
        SaTokenConfig config = new SaTokenConfig();
        config.setTokenName(properties.getTokenName());
        config.setJwtSecretKey(properties.getJwtSecretKey());
        config.setTimeout(properties.getAccessTokenTimeoutSeconds());
        return config;
    }

    /**
     * Sa-Token {@link AuthenticationPort} 的实现。
     *
     * @param properties 已绑定的 Sa-Token 属性
     * @return 认证适配器
     */
    @Bean
    public SaTokenAuthenticationAdapter saTokenAuthenticationAdapter(
            SaTokenAuthProperties properties) {
        return new SaTokenAuthenticationAdapter(properties);
    }

    @Bean
    public AuthenticationPort authenticationPort(SaTokenAuthenticationAdapter adapter) {
        return adapter;
    }

    /**
     * Sa-Token {@link TokenIssuerPort} 的实现。
     *
     * <p>与 {@link #authenticationPort} 共享同一个 {@link SaTokenAuthenticationAdapter}
     * 实例。该 Bean 由管理控制台控制器使用，用于签发控制台管理员登录所需的 JWT 令牌。</p>
     *
     * @return 令牌签发器
     */
    @Bean
    public TokenIssuerPort tokenIssuerPort(@Qualifier("saTokenAuthenticationAdapter") SaTokenAuthenticationAdapter adapter) {
        return adapter;
    }

    /**
     * Sa-Token {@link AuthorizationPort} 的实现（能力级别 ACL，ACL 数据为空或
     * 不可用时默认拒绝访问）。
     *
     * <p>注入 {@link AclRepository}，用于在启动时从 PostgreSQL 加载能力 ACL 条目，
     * 并支持运行时刷新。</p>
     *
     * @param aclRepository ACL 仓库
     * @return 授权适配器
     */
    @Bean
    public AuthorizationPort authorizationPort(
            AclRepository aclRepository,
            TelemetryPort telemetryPort,
            Environment environment) {
        int maxEntries = environment.getProperty(
                "gateway.auth.visibility-cache-max-entries", Integer.class, 10_000);
        long maxBytes = environment.getProperty(
                "gateway.auth.visibility-cache-max-bytes", Long.class,
                64L * 1024L * 1024L);
        return new SaTokenAuthorizationAdapter(
                false, aclRepository, maxEntries, maxBytes, telemetryPort);
    }

    AuthorizationPort authorizationPort(AclRepository aclRepository) {
        return new SaTokenAuthorizationAdapter(false, aclRepository);
    }
}
