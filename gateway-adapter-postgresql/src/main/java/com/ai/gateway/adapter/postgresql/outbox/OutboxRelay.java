package com.ai.gateway.adapter.postgresql.outbox;

import com.ai.gateway.domain.port.OutboxPort.OutboxEvent;
import com.ai.gateway.domain.port.OutboxExporterPort;
import org.springframework.beans.factory.ObjectProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * 后台中继器，轮询事务性 Outbox 并将事件导出到外部目标。
 *
 * <p>中继器实现至少一次（at-least-once）投递语义：事件从 Outbox 中轮询出来，导出到下游系统，
 * 仅当成功时才标记为已导出。若下游系统不可用，事件会在本地累积并触发告警——事件绝不会被丢弃。</p>
 *
 * <p><b>背压：</b> 若本地积压超过可配置的阈值，每个轮询周期都会记录一条告警。中继器不会丢弃
 * 事件，而是持续重试直到下游系统恢复。</p>
 *
 * <p>实际的导出逻辑（例如发布到 Kafka 或发送到 SIEM）委托给配置的 {@link OutboxExporterPort}。
 * 若没有导出器，中继器会刻意将事件保持为待处理状态。</p>
 *
 * @author cmiracle@163.com
 * @since 0.1.0
 */
@Slf4j
@Component
public class OutboxRelay {

    private static final int DEFAULT_BATCH_SIZE = 100;
    private static final int DEFAULT_BACKLOG_ALERT_THRESHOLD = 1000;

    private static final String SQL_COUNT_PENDING =
            "SELECT COUNT(*) FROM outbox_event WHERE status = 'PENDING'";

    private final JdbcOutboxPort outboxPort;
    private final JdbcTemplate jdbcTemplate;
    private final int batchSize;
    private final int backlogAlertThreshold;
    private final OutboxExporterPort exporter;

    /**
     * 使用默认配置构造一个新的 OutboxRelay。
     *
     * @param jdbcTemplate 用于数据库访问的 Spring JDBC 模板
     */
    @org.springframework.beans.factory.annotation.Autowired
    public OutboxRelay(JdbcTemplate jdbcTemplate, ObjectProvider<OutboxExporterPort> exporterProvider) {
        this(jdbcTemplate, exporterProvider.getIfAvailable(),
                DEFAULT_BATCH_SIZE, DEFAULT_BACKLOG_ALERT_THRESHOLD);
    }

    public OutboxRelay(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, null, DEFAULT_BATCH_SIZE, DEFAULT_BACKLOG_ALERT_THRESHOLD);
    }

    /**
     * 使用自定义配置构造一个新的 OutboxRelay。
     *
     * @param jdbcTemplate 用于数据库访问的 Spring JDBC 模板
     * @param batchSize 每个轮询周期最多轮询的事件数
     * @param backlogAlertThreshold 触发背压告警的待处理数量阈值
     */
    public OutboxRelay(JdbcTemplate jdbcTemplate, int batchSize, int backlogAlertThreshold) {
        this(jdbcTemplate, null, batchSize, backlogAlertThreshold);
    }

    public OutboxRelay(JdbcTemplate jdbcTemplate, OutboxExporterPort exporter,
                       int batchSize, int backlogAlertThreshold) {
        Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        this.jdbcTemplate = jdbcTemplate;
        this.outboxPort = new JdbcOutboxPort(jdbcTemplate);
        this.batchSize = batchSize;
        this.backlogAlertThreshold = backlogAlertThreshold;
        this.exporter = exporter;
    }

    /**
     * 周期性轮询 Outbox 并导出待处理事件。
     *
     * <p>默认每 5 秒运行一次。每个周期：</p>
     * <ol>
     * <li>检查待处理积压，若超过阈值则告警。</li>
     * <li>轮询一批未导出的事件。</li>
     * <li>将每个事件导出到外部目标。</li>
     * <li>将成功导出的事件标记为已导出。</li>
     * </ol>
     */
    @Scheduled(fixedDelay = 5000)
    public void relay() {
        checkBacklog();

        if (exporter == null) {
            log.debug("Outbox export is disabled; pending events remain available for a configured exporter");
            return;
        }

        List<OutboxEvent> events = outboxPort.pollUnexported(batchSize);
        if (events.isEmpty()) {
            return;
        }

        log.debug("OutboxRelay polled {} events", events.size());

        int exported = 0;
        int failed = 0;
        for (OutboxEvent event : events) {
            try {
                exporter.export(event);
                outboxPort.markExported(event.id());
                exported++;
            } catch (Exception e) {
                log.error("Failed to export outbox event id={}, type={}: {}",
                        event.id(), event.eventType(), e.getMessage(), e);
                failed++;
            }
        }

        log.debug("OutboxRelay cycle complete: exported={}, failed={}, total={}",
                exported, failed, events.size());
    }

    /**
     * 检查待处理积压，并在超过阈值时记录告警。
     *
     * <p>说明：若下游系统不可用，本地 Outbox 会累积并告警；事件绝不能被丢弃。</p>
     */
    private void checkBacklog() {
        Long pendingCount = jdbcTemplate.queryForObject(SQL_COUNT_PENDING, Long.class);
        if (pendingCount != null && pendingCount > backlogAlertThreshold) {
            log.warn("Outbox backlog alert: {} pending events exceed threshold {}",
                    pendingCount, backlogAlertThreshold);
        }
    }

}
