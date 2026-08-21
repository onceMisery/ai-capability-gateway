package com.ai.gateway.bootstrap.config;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.dao.SaTokenDaoRedissonJackson;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 将 Sa-Token 的会话存储接入共享 Redis 基础设施。
 *
 * <p>仅当同时设置 {@code gateway.auth.provider=sa-token} 与
 * {@code gateway.cache.provider=redis} 时生效。它注册一个
 * {@link SaTokenDaoRedissonJackson}（使用与快照缓存、分布式锁相同的
 * {@link RedissonClient} 初始化）作为 Sa-Token 的全局 {@code SaTokenDao}，
 * 从而使 Sa-Token 会话按技术选型文档 §12 的确认结论持久化到 Redis
 * （Sa-Token 会话存储 = Redis）。</p>
 *
 * <p>网关的主认证路径仍保持无状态 JWT 校验（确认结论 #2）；该 DAO 额外使
 * Sa-Token 基于会话的操作（登录/登出、踢下线）在集成选择使用时可共享同一
 * Redis 实例。</p>
 *
 * @since 0.1.0
 * @author cmiracle@163.com
 */
@Configuration
@ConditionalOnExpression("'${gateway.auth.provider}' == 'sa-token' && '${gateway.cache.provider}' == 'redis'")
public class SaTokenRedisDaoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SaTokenRedisDaoConfiguration.class);

    /**
     * 创建并注册基于 Redisson 的 Sa-Token DAO。
     *
     * @param redissonClient 共享的 Redisson 客户端
     * @return 基于 Redis 的 Sa-Token DAO
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
