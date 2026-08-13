package com.ai.gateway.testprovider;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Standalone Spring Boot + Dubbo test provider used for compatibility testing
 * (design document ).
 *
 * <p>This service simulates real business Dubbo APIs without requiring the actual
 * business API JARs on the gateway classpath. It exposes generic {@code Map}-based
 * interfaces that return the platform standard Envelope structure so the gateway's
 * invocation, normalization and governance logic can be exercised end to end.</p>
 */
@SpringBootApplication
@EnableDubbo
public class TestProviderApplication {

    public static void main(String[] args) {
        SpringApplication.run(TestProviderApplication.class, args);
    }
}
