package com.ai.gateway.bootstrap.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Guarantees that Java 8 date/time types such as {@link java.time.Instant} are
 * serializable through the shared application {@code ObjectMapper} used by the
 * Spring MVC {@code MappingJackson2HttpMessageConverter} (and every other
 * consumer, e.g. the catalog snapshot mapper).
 *
 * <p>Spring Boot auto-registers {@code JavaTimeModule} only when the
 * {@code jackson-datatype-jsr310} artifact is discovered on the classpath, and
 * that auto-registration has proven unreliable in this build. Defining an
 * explicit {@code ObjectMapper} bean here removes that dependency on
 * autodiscovery.</p>
 *
 * <p>The mapper is built from the {@link Jackson2ObjectMapperBuilder} bean that
 * Spring Boot itself configures (with all {@code spring.jackson.*} properties
 * and {@code Jackson2ObjectMapperBuilderCustomizer} beans already applied), so
 * existing settings such as {@code non_null} inclusion are preserved; we only
 * add the JSR-310 module and disable timestamp-style date serialization.</p>
 *
 * @since 0.1.0
 */
@Configuration
public class JacksonConfiguration {

    @Bean
    @Primary
    public ObjectMapper objectMapper(Jackson2ObjectMapperBuilder builder) {
        return builder
                .modulesToInstall(new JavaTimeModule())
                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
    }
}
