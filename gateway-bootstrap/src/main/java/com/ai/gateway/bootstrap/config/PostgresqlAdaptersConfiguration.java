package com.ai.gateway.bootstrap.config;

import com.ai.gateway.adapter.postgresql.audit.JdbcAuditPort;
import com.ai.gateway.adapter.postgresql.audit.JdbcAuditQueryAdapter;
import com.ai.gateway.adapter.postgresql.audit.JdbcStatsQueryAdapter;
import com.ai.gateway.adapter.postgresql.outbox.DataRetentionScheduler;
import com.ai.gateway.adapter.postgresql.outbox.JdbcOutboxPort;
import com.ai.gateway.adapter.postgresql.outbox.OutboxRelay;
import com.ai.gateway.adapter.postgresql.repository.JdbcAclRepository;
import com.ai.gateway.adapter.postgresql.repository.JdbcEnvelopeProfileRegistry;
import com.ai.gateway.adapter.postgresql.repository.JdbcInteractionRepository;
import com.ai.gateway.adapter.postgresql.repository.JdbcManifestRepository;
import com.ai.gateway.adapter.postgresql.repository.JdbcOperationRepository;
import com.ai.gateway.adapter.postgresql.repository.CatalogReadBudget;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * PostgreSQL 适配器装配（gateway-adapter-postgresql）。
 *
 * <p>聚合持久化侧的全部适配器：Manifest/操作/交互仓库、信封配置注册表、
 * 审计端口、Outbox 中继与数据保留调度器。审计微批提交与 Outbox 导出
 * 依赖 {@link org.springframework.scheduling.annotation.EnableScheduling}
 * 提供的定时任务能力。</p>
 *
 * @since 0.1.0
 */
@Configuration
@Import({
        JdbcManifestRepository.class,
        JdbcOperationRepository.class,
        JdbcInteractionRepository.class,
        JdbcAclRepository.class,
        JdbcEnvelopeProfileRegistry.class,
        JdbcAuditPort.class,
        JdbcAuditQueryAdapter.class,
        JdbcStatsQueryAdapter.class,
        JdbcOutboxPort.class,
        DataRetentionScheduler.class,
        OutboxRelay.class,
})
public class PostgresqlAdaptersConfiguration {

    @Bean
    public CatalogReadBudget catalogReadBudget(GatewayProperties properties) {
        GatewayProperties.Agent agent = properties.getAgent();
        long timeoutMs = agent.getCatalogIoQueryTimeoutMs();
        if (timeoutMs <= 0L) {
            throw new IllegalArgumentException("catalogIoQueryTimeoutMs must be positive");
        }
        long timeoutSecondsValue = Math.min(Integer.MAX_VALUE, (timeoutMs - 1L) / 1000L + 1L);
        int timeoutSeconds = Math.toIntExact(Math.max(1L, timeoutSecondsValue));
        return new CatalogReadBudget(agent.getCatalogIoMaxRows(), timeoutSeconds,
                agent.getCatalogIoMaxPayloadBytes());
    }
}
