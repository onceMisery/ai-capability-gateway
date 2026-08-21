package com.ai.gateway.bootstrap.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Strongly typed owner of the gateway's non-sensitive runtime configuration.
 * Components must consume this object instead of re-declaring fallback values.
 */
@ConfigurationProperties(prefix = "gateway")
public class GatewayProperties {

    private String environment = "";
    private int maxRequestSizeBytes = 65_536;
    private long maxResponseBytes = 1_048_576L;
    private int defaultTimeoutMs = 15_000;
    private String secretFilePath = "";
    private Provider auth = new Provider();
    private Provider cache = new Provider();
    private Provider ratelimit = new Provider();
    private Llm llm = new Llm();
    private Operation operation = new Operation();
    private Agent agent = new Agent();
    private Redis redis = new Redis();
    private Audit audit = new Audit();
    private Snapshot snapshot = new Snapshot();
    private Sentinel sentinel = new Sentinel();
    private Protocol protocol = new Protocol();

    public String getEnvironment() {
        return environment;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    public int getMaxRequestSizeBytes() {
        return maxRequestSizeBytes;
    }

    public void setMaxRequestSizeBytes(int maxRequestSizeBytes) {
        this.maxRequestSizeBytes = maxRequestSizeBytes;
    }

    public long getMaxResponseBytes() {
        return maxResponseBytes;
    }

    public void setMaxResponseBytes(long maxResponseBytes) {
        this.maxResponseBytes = maxResponseBytes;
    }

    public int getDefaultTimeoutMs() {
        return defaultTimeoutMs;
    }

    public String getSecretFilePath() { return secretFilePath; }
    public void setSecretFilePath(String secretFilePath) { this.secretFilePath = secretFilePath; }

    public void setDefaultTimeoutMs(int defaultTimeoutMs) {
        this.defaultTimeoutMs = defaultTimeoutMs;
    }

    public Provider getAuth() {
        return auth;
    }

    public void setAuth(Provider auth) {
        this.auth = auth;
    }

    public Provider getCache() {
        return cache;
    }

    public void setCache(Provider cache) {
        this.cache = cache;
    }

    public Provider getRatelimit() {
        return ratelimit;
    }

    public void setRatelimit(Provider ratelimit) {
        this.ratelimit = ratelimit;
    }

    public Llm getLlm() {
        return llm;
    }

    public void setLlm(Llm llm) {
        this.llm = llm;
    }

    public Operation getOperation() {
        return operation;
    }

    public void setOperation(Operation operation) {
        this.operation = operation;
    }

    public Agent getAgent() {
        return agent;
    }

    public void setAgent(Agent agent) {
        this.agent = agent;
    }

    public Redis getRedis() {
        return redis;
    }

    public void setRedis(Redis redis) {
        this.redis = redis;
    }

    public Audit getAudit() {
        return audit;
    }

    public void setAudit(Audit audit) {
        this.audit = audit;
    }

    public Snapshot getSnapshot() {
        return snapshot;
    }

    public void setSnapshot(Snapshot snapshot) {
        this.snapshot = snapshot;
    }

    public Sentinel getSentinel() { return sentinel; }
    public void setSentinel(Sentinel sentinel) { this.sentinel = sentinel; }

    public Protocol getProtocol() { return protocol; }
    public void setProtocol(Protocol protocol) { this.protocol = protocol; }

    @Getter
    @Setter
    public static class Protocol {
        private java.util.Map<String, String> restEndpoints = new java.util.LinkedHashMap<>();
        private java.util.Map<String, String> grpcEndpoints = new java.util.LinkedHashMap<>();
        private java.util.Map<String, String> grpcDescriptorSets = new java.util.LinkedHashMap<>();
    }

    public static class Provider {
        private String provider = "";
        private SaToken saToken = new SaToken();
        private ConsoleAdmin consoleAdmin = new ConsoleAdmin();

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public SaToken getSaToken() {
            return saToken;
        }

        public void setSaToken(SaToken saToken) {
            this.saToken = saToken;
        }

        public ConsoleAdmin getConsoleAdmin() {
            return consoleAdmin;
        }

        public void setConsoleAdmin(ConsoleAdmin consoleAdmin) {
            this.consoleAdmin = consoleAdmin;
        }
    }

    public static class SaToken {
        private String jwtSecretKey = "";

        public String getJwtSecretKey() {
            return jwtSecretKey;
        }

        public void setJwtSecretKey(String jwtSecretKey) {
            this.jwtSecretKey = jwtSecretKey;
        }
    }

    public static class ConsoleAdmin {
        private String username = "";
        private String password = "";

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }

    public static class Llm {
        private String endpoint = "";
        private String apiKey = "";
        private String model = "";
        private double temperature = 0.1d;
        private int maxTokens = 4096;

        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public double getTemperature() { return temperature; }
        public void setTemperature(double temperature) { this.temperature = temperature; }
        public int getMaxTokens() { return maxTokens; }
        public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
    }

    public static class Operation {
        private String confirmationSecret = "";

        public String getConfirmationSecret() { return confirmationSecret; }
        public void setConfirmationSecret(String confirmationSecret) {
            this.confirmationSecret = confirmationSecret;
        }
    }

    @Getter
    @Setter
    public static class Agent {
        private String toolRefCurrentKeyId = "k1";
        private String toolRefSecret = "";
        private String toolRefPreviousKeyId = "";
        private String toolRefPreviousSecret = "";
        private long toolRefTtlSeconds = 120L;
        private long resolveTimeoutMs = 100L;
        private int pendingConfirmationMaxEntries = 10_000;
        private int turnMaxEntries = 10_000;
        private int resolveMaxConcurrent = 64;
        private int resolveMaxQueue = 64;
        private int catalogMaxCapabilities = 10_000;
        private long catalogMaxIndexBytes = 67_108_864L;
        private long catalogMaxProcessMemoryBytes = 536_870_912L;
        private long catalogBuildTimeoutMs = 5_000L;
        private long catalogLeaseHoldTimeoutMs = 1_000L;
        private int catalogIoMaxRows = 10_000;
        private long catalogIoQueryTimeoutMs = 3_000L;
        private long catalogIoMaxPayloadBytes = 134_217_728L;
        private int mcpMaxSessions = 1_000;
        private long mcpSessionIdleSeconds = 1_800L;
        private String mcpSecurityMode = "READ_ONLY";
        private long mcpCallTimeoutMs = 30_000L;
        private long mcpCloseTimeoutMs = 5_000L;
        private String mcpNodeId = "local";
        private double mcpSseQps = 100d;
        private double mcpMessageQps = 500d;
        private double mcpResolveQps = 200d;
        private double mcpCallQps = 200d;
        private java.util.List<McpTrustedClient> mcpTrustedClients =
                new java.util.ArrayList<>();
    }

    @Getter
    @Setter
    public static class McpTrustedClient {
        private String clientId = "";
        private String tokenFingerprint = "";
        private String tokenAssurance = "HIGH";
        private String confirmationChannel = "HOST_UI";
        private boolean enabled = true;
        private String expiresAt = "";
    }

    public static class Redis {
        private String address = "";
        private String password = "";
        private int database;
        private SnapshotCache snapshot = new SnapshotCache();

        public String getAddress() {
            return address;
        }

        public void setAddress(String address) {
            this.address = address;
        }

        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public int getDatabase() { return database; }
        public void setDatabase(int database) { this.database = database; }

        public SnapshotCache getSnapshot() {
            return snapshot;
        }

        public void setSnapshot(SnapshotCache snapshot) {
            this.snapshot = snapshot;
        }
    }

    public static class SnapshotCache {
        private int localTtlSeconds = 30;

        public int getLocalTtlSeconds() {
            return localTtlSeconds;
        }

        public void setLocalTtlSeconds(int localTtlSeconds) {
            this.localTtlSeconds = localTtlSeconds;
        }
    }

    public static class Audit {
        private int batchSize = 50;
        private int batchWaitMillis = 5;

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public int getBatchWaitMillis() {
            return batchWaitMillis;
        }

        public void setBatchWaitMillis(int batchWaitMillis) {
            this.batchWaitMillis = batchWaitMillis;
        }
    }

    public static class Snapshot {
        private long maxLagMillis = 30_000L;

        public long getMaxLagMillis() {
            return maxLagMillis;
        }

        public void setMaxLagMillis(long maxLagMillis) {
            this.maxLagMillis = maxLagMillis;
        }
    }

    public static class Sentinel {
        private double globalQps = 2000d;
        private double llmQps = 20d;
        private int llmMaxQueueingMs = 2000;

        public double getGlobalQps() { return globalQps; }
        public void setGlobalQps(double globalQps) { this.globalQps = globalQps; }
        public double getLlmQps() { return llmQps; }
        public void setLlmQps(double llmQps) { this.llmQps = llmQps; }
        public int getLlmMaxQueueingMs() { return llmMaxQueueingMs; }
        public void setLlmMaxQueueingMs(int llmMaxQueueingMs) {
            this.llmMaxQueueingMs = llmMaxQueueingMs;
        }
    }
}
