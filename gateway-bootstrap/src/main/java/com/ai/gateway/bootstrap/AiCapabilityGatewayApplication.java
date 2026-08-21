package com.ai.gateway.bootstrap;

import com.ai.gateway.bootstrap.config.AuthProviderConfiguration;
import com.ai.gateway.bootstrap.config.CatalogProviderConfiguration;
import com.ai.gateway.bootstrap.config.DubboAdaptersConfiguration;
import com.ai.gateway.bootstrap.config.LlmAdaptersConfiguration;
import com.ai.gateway.bootstrap.config.PostgresqlAdaptersConfiguration;
import com.ai.gateway.bootstrap.config.RateLimitProviderConfiguration;
import com.ai.gateway.bootstrap.config.WebAdaptersConfiguration;
import com.ai.gateway.bootstrap.config.RestGrpcAdaptersConfiguration;
import cn.dev33.satoken.dao.SaTokenDaoRedissonJackson;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * AI 能力网关的 Spring Boot 启动入口。
 *
 * <p>该类在不依赖大范围组件扫描的前提下，装配所有适配器与用例。
 * {@code @SpringBootApplication} 注解仅扫描 {@code com.ai.gateway.bootstrap}
 * 包（即本类所在包及其子包）下的引导本地 {@code @Component} 与
 * {@code @Configuration} 类，例如 {@link
 * com.ai.gateway.bootstrap.config.BeanConfig BeanConfig}、{@link
 * com.ai.gateway.bootstrap.config.SchemaValidatorAdapter SchemaValidatorAdapter}、
 * {@link com.ai.gateway.bootstrap.config.SecretManagerAdapter SecretManagerAdapter}
 * 以及 {@link com.ai.gateway.bootstrap.config.GatewayHealthIndicator
 * GatewayHealthIndicator}。</p>
 *
 * <p>位于其它包中的适配器实现通过 {@link Import} 显式引入，并按适配器域归组到
 * {@code com.ai.gateway.bootstrap.config} 下的专用配置类中：可插拔提供者
 * （认证、目录、限流）与适配器组（PostgreSQL、Dubbo、LLM HTTP、Web）。
 * 应用层保持与框架无关——bootstrap 模块是适配器类与应用用例装配的唯一场所。</p>
 *
 * <p>{@link EnableScheduling} 激活诸如发件箱中继与数据留存清理等定时任务。</p>
 *
 * @since 0.1.0
 * @author cmiracle@163.com
 */
@SpringBootApplication(exclude = SaTokenDaoRedissonJackson.class)
@EnableScheduling
@EnableConfigurationProperties({
        com.ai.gateway.bootstrap.config.GatewayProperties.class,
        com.ai.gateway.bootstrap.config.PayloadLimitsProperties.class
})
@Import({
        // --- 可插拔提供者装配（认证 / 目录 / 限流）---
        // 每个提供者组包含互斥的实现，由其各自的 @ConditionalOnProperty 注解激活。
        AuthProviderConfiguration.class,
        CatalogProviderConfiguration.class,
        RateLimitProviderConfiguration.class,

        // --- 适配器层装配（按适配器域分组）---
        PostgresqlAdaptersConfiguration.class,
        DubboAdaptersConfiguration.class,
        LlmAdaptersConfiguration.class,
        RestGrpcAdaptersConfiguration.class,
        WebAdaptersConfiguration.class,
})
public class AiCapabilityGatewayApplication {

    /**
     * 启动 AI 能力网关 Spring Boot 应用。
     *
     * @param args 透传给 Spring Boot 的命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(AiCapabilityGatewayApplication.class, args);
    }
}
