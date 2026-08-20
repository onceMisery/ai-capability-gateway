package com.ai.gateway.bootstrap.config;

import com.ai.gateway.domain.port.SnapshotNotifier;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 目录直连模式的条件装配测试。
 *
 * @author cmiracle@163.com
 */
class DirectCatalogConfigurationTest {

    @Test
    void cacheStubProvidesNotifierIndependentlyOfAuthenticationProvider() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.setEnvironment(new MockEnvironment()
                    .withProperty("gateway.cache.provider", "stub")
                    .withProperty("gateway.auth.provider", "sa-token"));
            context.registerBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class));
            context.register(DirectCatalogConfiguration.class);
            context.refresh();

            assertThat(context.getBeansOfType(SnapshotNotifier.class)).hasSize(1);
        }
    }
}
