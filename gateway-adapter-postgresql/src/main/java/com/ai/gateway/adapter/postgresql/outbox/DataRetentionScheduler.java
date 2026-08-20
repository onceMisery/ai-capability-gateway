package com.ai.gateway.adapter.postgresql.outbox;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 定时执行的数据保留清理，用于删除已过期的交互会话以及过期的审计事件。
 *
 * <p>周期性运行以删除：</p>
 * <ul>
 * <li><b>已过期的 NL 交互：</b> {@code expires_at} 已超时的记录。这些是短期有效的澄清
 * 会话，不再有效。</li>
 * <li><b>过期的审计事件：</b> 超过可配置保留期（默认 90 天）的记录。审计事件在删除前会通过
 * outbox 导出到外部 SIEM，因此业务表可被裁剪以控制存储增长。</li>
 * </ul>
 *
 * <p>审计事件的保留期可配置。在生产环境中，该值应基于合规要求以及容量结论进行设置。</p>
 *
 * @author cmiracle@163.com
 * @since 0.1.0
 */
@Slf4j
@Component
public class DataRetentionScheduler {

    private static final int DEFAULT_AUDIT_RETENTION_DAYS = 90;

    private static final String SQL_DELETE_EXPIRED_INTERACTIONS =
            "DELETE FROM nl_interaction WHERE expires_at < NOW()";

    private static final String SQL_DELETE_AGED_AUDIT_EVENTS =
            "DELETE FROM audit_event ae " +
            "WHERE ae.timestamp < (NOW() - ? * INTERVAL '1 day') " +
            "AND EXISTS (SELECT 1 FROM outbox_event oe " +
            "WHERE oe.audit_event_id = ae.event_id AND oe.status = 'EXPORTED')";

    private final JdbcTemplate jdbcTemplate;
    private final int auditRetentionDays;

    /**
     * 使用默认 90 天审计保留期构造一个新的 DataRetentionScheduler。
     *
     * @param jdbcTemplate 用于数据库访问的 Spring JDBC 模板
     */
    @org.springframework.beans.factory.annotation.Autowired
    public DataRetentionScheduler(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, DEFAULT_AUDIT_RETENTION_DAYS);
    }

    /**
     * 使用自定义审计保留期构造一个新的 DataRetentionScheduler。
     *
     * @param jdbcTemplate 用于数据库访问的 Spring JDBC 模板
     * @param auditRetentionDays 审计事件删除前的保留天数
     */
    public DataRetentionScheduler(JdbcTemplate jdbcTemplate, int auditRetentionDays) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        this.auditRetentionDays = auditRetentionDays;
    }

    /**
     * 周期性清理已过期的交互会话与过期的审计事件。
     *
     * <p>默认每 24 小时运行一次。清理操作是幂等的，可安全并发执行——每条 DELETE 都是独立操作。</p>
     */
    @Scheduled(fixedDelay = 86_400_000)
    public void cleanup() {
        cleanupExpiredInteractions();
        cleanupAgedAuditEvents();
    }

    /**
     * 删除已过期的 NL 交互会话。
     *
     * <p>说明：澄清交互具有较短的 TTL。已过期的会话不再有效，可安全移除。</p>
     */
    private void cleanupExpiredInteractions() {
        try {
            int deleted = jdbcTemplate.update(SQL_DELETE_EXPIRED_INTERACTIONS);
            if (deleted > 0) {
                log.info("DataRetention: deleted {} expired nl_interaction records", deleted);
            }
        } catch (Exception e) {
            log.error("DataRetention: failed to clean expired nl_interaction records: {}",
                    e.getMessage(), e);
        }
    }

    /**
     * 删除超过保留期的审计事件。
     *
     * <p>说明：审计事件在删除前会通过 outbox 导出到外部 SIEM。业务表可被裁剪以控制存储增长。
     * 保留期可基于合规要求与容量结论进行配置。</p>
     */
    private void cleanupAgedAuditEvents() {
        try {
            int deleted = jdbcTemplate.update(SQL_DELETE_AGED_AUDIT_EVENTS, auditRetentionDays);
            if (deleted > 0) {
                log.info("DataRetention: deleted {} audit_event records older than {} days",
                        deleted, auditRetentionDays);
            }
        } catch (Exception e) {
            log.error("DataRetention: failed to clean aged audit_event records: {}",
                    e.getMessage(), e);
        }
    }
}
