package com.ai.gateway.bootstrap.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FlywayBaselinePolicyTest 类。
 *
 * @author cmiracle@163.com
 */
class FlywayBaselinePolicyTest {

    @Test
    void applicationProfilesNeverSilentlyBaselineExistingDatabases() throws IOException {
        assertThat(resource("application.yml")).contains("baseline-on-migrate: false");
        assertThat(resource("application-local.yml.example")).contains("baseline-on-migrate: false");
    }

    private String resource(String name) throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(name)) {
            assertThat(input).as("classpath resource %s", name).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
