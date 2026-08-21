package com.ai.gateway.bootstrap.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 保证 {@link java.time.Instant} 等 Java 8 日期/时间类型可通过共享的
 * 应用级 {@code ObjectMapper} 序列化，该 {@code ObjectMapper} 被 Spring MVC 的
 * {@code MappingJackson2HttpMessageConverter}（以及目录快照映射器等其它消费者）使用。
 *
 * <p>Spring Boot 仅在类路径上发现 {@code jackson-datatype-jsr310} 时才会自动注册
 * {@code JavaTimeModule}，而本构建已证实自动注册不可靠。此处显式定义
 * {@code ObjectMapper} Bean，从而解除对自动发现的依赖。</p>
 *
 * <p>该映射器基于 Spring Boot 自身配置的 {@link Jackson2ObjectMapperBuilder} Bean
 * （已应用所有 {@code spring.jackson.*} 属性与 {@code Jackson2ObjectMapperBuilderCustomizer}
 * Bean）构建，因此 {@code non_null} 等既有配置得以保留；我们只额外添加 JSR-310 模块
 * 并禁用基于时间戳的日期序列化。</p>
 *
 * @since 0.1.0
 * @author cmiracle@163.com
 */
@Configuration
public class JacksonConfiguration {

    @Bean
    @Primary
    public ObjectMapper objectMapper(Jackson2ObjectMapperBuilder builder) {
        return builder
                .modulesToInstall(new JavaTimeModule())
                .featuresToEnable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
    }
}
