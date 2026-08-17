package com.ai.gateway.adapter.redis;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Strongly typed Redis adapter view of the shared gateway configuration. */
@ConfigurationProperties(prefix = "gateway")
public class RedisGatewayProperties {

    private String environment = "";
    private Redis redis = new Redis();

    public String getEnvironment() {
        return environment;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    public Redis getRedis() {
        return redis;
    }

    public void setRedis(Redis redis) {
        this.redis = redis;
    }

    public static class Redis {
        private String address = "";
        private String password = "";
        private int database;
        private Snapshot snapshot = new Snapshot();

        public String getAddress() { return address; }
        public void setAddress(String address) { this.address = address; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public int getDatabase() { return database; }
        public void setDatabase(int database) { this.database = database; }
        public Snapshot getSnapshot() { return snapshot; }
        public void setSnapshot(Snapshot snapshot) { this.snapshot = snapshot; }
    }

    public static class Snapshot {
        private long localTtlSeconds = 30;

        public long getLocalTtlSeconds() { return localTtlSeconds; }
        public void setLocalTtlSeconds(long localTtlSeconds) {
            this.localTtlSeconds = localTtlSeconds;
        }
    }
}
