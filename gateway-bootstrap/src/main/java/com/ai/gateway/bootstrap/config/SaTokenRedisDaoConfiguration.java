package com.ai.gateway.bootstrap.config;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.dao.SaTokenDaoRedissonJackson;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires Sa-Token's session storage onto the shared Redis infrastructure.
 *
 * <p>Activated only when both {@code gateway.auth.provider=sa-token} and
 * {@code gateway.cache.provider=redis} are set. It registers a
 * {@link SaTokenDaoRedissonJackson} — initialized with the same
 * {@link RedissonClient} used by the snapshot cache and distributed locks —
 * as Sa-Token's global {@code SaTokenDao}, so Sa-Token sessions persist to
 * Redis per the tech-selection doc §12 confirmed decision (Sa-Token session
 * storage = Redis).</p>
 *
 * <p>The gateway's primary authentication path remains stateless JWT
 * verification (confirmed decision #2); this DAO additionally enables
 * Sa-Token's session-based operations (login/logout, kick-out) to share the
 * Redis instance when an integration chooses to use them.</p>
 *
 * @since 0.1.0
 */
@Configuration
@ConditionalOnProperty(name = "gateway.auth.provider", havingValue = "sa-token")
public class SaTokenRedisDaoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SaTokenRedisDaoConfiguration.class);

    /**
     * Creates and registers the Redisson-backed Sa-Token DAO.
     *
     * @param redissonClient the shared Redisson client
     * @return the Sa-Token DAO backed by Redis
     */
    @Bean
    public SaTokenDaoRedissonJackson saTokenDao(RedissonClient redissonClient) {
        SaTokenDaoRedissonJackson dao = new SaTokenDaoRedissonJackson();
        dao.init(redissonClient);
        SaManager.setSaTokenDao(dao);
        log.info("Sa-Token session storage wired to Redis (Redisson)");
        return dao;
    }
}
