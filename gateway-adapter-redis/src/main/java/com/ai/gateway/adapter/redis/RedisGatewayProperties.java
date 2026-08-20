package com.ai.gateway.adapter.redis;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 共享网关配置面向 Redis 适配器的强类型视图。
 *
 * @author cmiracle@163.com
 * @since 0.1.0
 */
@Setter
@Getter
@ConfigurationProperties(prefix = "gateway")
public class RedisGatewayProperties {

    private String environment = "";
    private Redis redis = new Redis();

    @Setter
    @Getter
    public static class Redis {
        private String address = "";
        private String password = "";
        private int database;
        private Snapshot snapshot = new Snapshot();

    }

    @Setter
    @Getter
    public static class Snapshot {
        private long localTtlSeconds = 30;

    }
}
