package com.ai.gateway.adapter.redis;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RedisGatewayProperties} 的绑定测试，验证在不依赖默认值回退的情况下完整绑定 Redis 配置。
 *
 * @author cmiracle@163.com
 */
class RedisGatewayPropertiesTest {

    @Test
    void bindsCompleteRedisConfigurationWithoutValueFallbacks() {
        RedisGatewayProperties properties = new Binder(new MapConfigurationPropertySource(Map.of(
                "gateway.environment", "staging",
                "gateway.redis.address", "redis://cache:6379",
                "gateway.redis.password", "secret",
                "gateway.redis.database", "4",
                "gateway.redis.snapshot.local-ttl-seconds", "45")))
                .bind("gateway", Bindable.of(RedisGatewayProperties.class))
                .orElseThrow(() -> new IllegalStateException("redis gateway properties not bound"));

        assertThat(properties.getEnvironment()).isEqualTo("staging");
        assertThat(properties.getRedis().getAddress()).isEqualTo("redis://cache:6379");
        assertThat(properties.getRedis().getPassword()).isEqualTo("secret");
        assertThat(properties.getRedis().getDatabase()).isEqualTo(4);
        assertThat(properties.getRedis().getSnapshot().getLocalTtlSeconds()).isEqualTo(45);
    }
}
