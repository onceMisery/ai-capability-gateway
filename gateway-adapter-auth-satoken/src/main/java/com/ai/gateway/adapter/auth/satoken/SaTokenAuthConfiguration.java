package com.ai.gateway.adapter.auth.satoken;

import cn.dev33.satoken.config.SaTokenConfig;
import com.ai.gateway.domain.port.AuthenticationPort;
import com.ai.gateway.domain.port.AuthorizationPort;
import com.ai.gateway.domain.port.AclRepository;
import com.ai.gateway.domain.port.TokenIssuerPort;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Conditional Spring wiring for the Sa-Token authentication/authorization
 * adapter.
 *
 * <p>Activated only when {@code gateway.auth.provider=sa-token}. This is the
 * pluggability seam described in the tech-selection doc: swapping the
 * authentication provider is a configuration change plus a different
 * {@link AuthenticationPort}/{@link AuthorizationPort} bean — the Domain
 * ports stay untouched.</p>
 *
 * <p>The Sa-Token JWT secret is held in {@link SaTokenAuthProperties} and
 * passed to the Sa-Token JWT utilities explicitly by the adapter.</p>
 *
 * @since 0.1.0
 */
@Configuration
@ConditionalOnProperty(name = "gateway.auth.provider", havingValue = "sa-token")
public class SaTokenAuthConfiguration {

    /**
     * Binds {@code gateway.auth.sa-token.*} properties using the Spring
     * {@link Environment}.
     *
     * <p>Programmatic binding keeps the module free of the Spring Boot
     * {@code @ConfigurationProperties} machinery, consistent with the
     * tech-selection decision to avoid framework-level intrusion.</p>
     *
     * @param environment the Spring environment
     * @return the populated properties holder
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
     * Publishes a Sa-Token {@link SaTokenConfig} carrying the shared JWT
     * secret and token name.
     *
     * <p>The adapter passes the secret to the Sa-Token JWT utilities
     * explicitly on each call; this bean additionally exposes the values as
     * a Sa-Token config object for future integration with session storage
     * ({@code sa-token-dao-redisson}, milestone M2).</p>
     *
     * @param properties the bound Sa-Token properties
     * @return the Sa-Token configuration
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
     * The Sa-Token {@link AuthenticationPort} implementation.
     *
     * @param properties the bound Sa-Token properties
     * @return the authentication adapter
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
     * The Sa-Token {@link TokenIssuerPort} implementation.
     *
     * <p>Shares the same {@link SaTokenAuthenticationAdapter} instance as
     * {@link #authenticationPort}. This bean is consumed by the admin console
     * controller to issue JWT tokens for console admin login.</p>
     *
     * @param properties the bound Sa-Token properties
     * @return the token issuer
     */
    @Bean
    public TokenIssuerPort tokenIssuerPort(@Qualifier("saTokenAuthenticationAdapter") SaTokenAuthenticationAdapter adapter) {
        return adapter;
    }

    /**
     * The Sa-Token {@link AuthorizationPort} implementation (capability-level
     * ACL, initial-release degradation enabled).
     *
     * <p>Injects the {@link AclRepository} to load capability ACL entries
     * from PostgreSQL on startup and support runtime refresh.</p>
     *
     * @param aclRepository the ACL repository
     * @return the authorization adapter
     */
    @Bean
    public AuthorizationPort authorizationPort(AclRepository aclRepository) {
        return new SaTokenAuthorizationAdapter(true, aclRepository);
    }
}
