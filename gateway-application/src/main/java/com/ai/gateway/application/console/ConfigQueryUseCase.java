package com.ai.gateway.application.console;

import com.ai.gateway.domain.model.CacheStatus;
import com.ai.gateway.domain.model.GatewayConfig;
import com.ai.gateway.domain.model.RateLimitRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Use case for querying the gateway's non-sensitive runtime configuration
 * from the admin console.
 *
 * <p>Provides read-only views of:</p>
 * <ul>
 * <li>General gateway configuration (environment, providers, limits).</li>
 * <li>Sentinel rate-limit rules (flow and degrade).</li>
 * <li>Cache subsystem status.</li>
 * </ul>
 *
 * <p>Sensitive values (JWT secrets, API keys, database passwords) are
 * never included in the response.</p>
 *
 * <p>This class uses constructor injection and contains no Spring annotations.
 * It is thread-safe: it holds no mutable state.</p>
 *
 * @since 0.1.0
 */
public final class ConfigQueryUseCase {

    private static final Logger log = LoggerFactory.getLogger(ConfigQueryUseCase.class);

    private final GatewayConfig gatewayConfig;
    private final CacheStatus cacheStatus;
    private final List<RateLimitRule> rateLimitRules;

    /**
     * Constructs a new ConfigQueryUseCase.
     *
     * @param gatewayConfig the gateway configuration
     * @param cacheStatus the cache status
     * @param rateLimitRules the current Sentinel rate-limit rules
     */
    public ConfigQueryUseCase(GatewayConfig gatewayConfig,
                               CacheStatus cacheStatus,
                               List<RateLimitRule> rateLimitRules) {
        this.gatewayConfig = Objects.requireNonNull(gatewayConfig);
        this.cacheStatus = Objects.requireNonNull(cacheStatus);
        this.rateLimitRules = rateLimitRules != null ? List.copyOf(rateLimitRules) : List.of();
    }

    /**
     * Returns the gateway's non-sensitive configuration.
     *
     * @return the gateway configuration; never {@code null}
     */
    public GatewayConfig getGatewayConfig() {
        return gatewayConfig;
    }

    /**
     * Returns the cache subsystem status.
     *
     * @return the cache status; never {@code null}
     */
    public CacheStatus getCacheStatus() {
        return cacheStatus;
    }

    /**
     * Returns the current Sentinel rate-limit rules.
     *
     * @return the rate-limit rules; never {@code null}
     */
    public List<RateLimitRule> getRateLimitRules() {
        return rateLimitRules;
    }
}
