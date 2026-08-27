package com.ai.gateway.bootstrap.audit;

import com.ai.gateway.adapter.a2a.A2aTaskAuditRecorder;
import com.ai.gateway.domain.model.AuditEvent;
import com.ai.gateway.domain.model.AuditPlane;
import com.ai.gateway.domain.port.AuditPort;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 把 A2A 平面事件落到统一审计出口 {@link AuditPort} 上（设计 §3.8）。
 *
 * <p>这层映射刻意放在 bootstrap 而不是 {@code gateway-adapter-a2a} 里：A2A 模块只定义
 * 「平面需要一个可能失败的审计调用点」这件事（{@link A2aTaskAuditRecorder}），
 * 至于事件如何命名、明细如何序列化、落到哪个存储，是装配期的决定。若让 A2A 模块直接依赖
 * {@link AuditPort}，换审计后端就会波及协议适配器。</p>
 *
 * <p><b>平面标签取自事件类型自身</b>（{@code EventType.plane()}），本类不做入站/出站判定：
 * 判定一次就意味着可以判定错，而「出站委托被记成入站事件」这种归因错误在指标上表现为
 * 本网关暴露面故障率升高，排查方向会整体走偏。</p>
 *
 * <p><b>本类不吞异常。</b>{@link AuditPort} 的失效关闭语义（落库失败即抛出）必须原样透出，
 * 因为 {@code A2aServerTransportAdapter} 正是靠这个异常把终态降级成失败态，
 * 从而保证「审计没落库就不返回业务产物」。在这里 {@code catch} 一次，
 * 整条约束就会静默失效，而且失效形态是「一切照常返回，只是没有痕迹」——最难被发现的那种。</p>
 *
 * <p><b>{@code orgId} 恒为 0。</b>A2A 的调用方是工作负载而不是用户：租户号只存在于
 * 执行链内部由 {@code Principal} 派生的那一层，平面事件此时还没有它。
 * 从 A2A 消息体里读一个 {@code orgId} 填进来是被禁止的——那等于让对端自选审计归属。</p>
 *
 * <p>本类不可变且线程安全（{@link ObjectMapper} 在仅序列化时线程安全）。</p>
 *
 * @author cmiracle@163.com
 * @since 0.1.0
 */
public final class A2aAuditPortRecorder implements A2aTaskAuditRecorder {

    /** 审计明细里承载对端身份标签的字段名。 */
    private static final String FIELD_PEER = "peerAgentName";

    /** 审计明细里承载信任分级的字段名。 */
    private static final String FIELD_TRUST_TIER = "trustTier";

    /** 审计明细里承载任务标识的字段名。 */
    private static final String FIELD_TASK_ID = "taskId";

    /** 审计明细里承载审计侧原因码的字段名；<b>不得</b>出现在任何对端可见响应里。 */
    private static final String FIELD_REASON = "reasonCode";

    /** 明细里承载终态结果码的键，由传输适配器写入。 */
    private static final String DETAIL_RESULT_CODE = "resultCode";

    /** 明细序列化失败时的兜底结果码：宁可留一条少字段的痕迹，也不能没有痕迹。 */
    private static final String DETAILS_UNAVAILABLE = "DETAILS_UNAVAILABLE";

    private final AuditPort auditPort;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /**
     * @param auditPort    统一审计出口，不能为 {@code null}
     * @param objectMapper 明细序列化器，不能为 {@code null}
     */
    public A2aAuditPortRecorder(AuditPort auditPort, ObjectMapper objectMapper) {
        this(auditPort, objectMapper, Clock.systemUTC());
    }

    /**
     * @param auditPort    统一审计出口，不能为 {@code null}
     * @param objectMapper 明细序列化器，不能为 {@code null}
     * @param clock        时钟，不能为 {@code null}
     */
    public A2aAuditPortRecorder(AuditPort auditPort, ObjectMapper objectMapper, Clock clock) {
        this.auditPort = Objects.requireNonNull(auditPort, "auditPort must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * 记录一次入站平面事件。
     *
     * @param entry 事件内容，不能为 {@code null}
     * @throws RuntimeException 落库失败；调用方据此拒绝返回业务数据
     */
    @Override
    public void record(Entry entry) {
        Objects.requireNonNull(entry, "entry must not be null");
        auditPort.recordEvent(new AuditEvent(
                UUID.randomUUID().toString(),
                entry.eventType().wireName(),
                clock.instant(),
                entry.identity().peerDigest(),
                0L,
                entry.taskContext().rootRequestId(),
                null, null, null, null,
                0L, null, null,
                resultCode(entry),
                0L,
                detailsJson(entry)));
    }

    /**
     * 派生稳定结果码。
     *
     * <p>拒绝事件用原因码，终态事件用传输适配器写入的 {@code resultCode}。两者不会同时出现，
     * 因此这里不是取舍而是取其一：审计的查询侧需要一个恒定位置来回答「这条请求最后怎么了」。</p>
     */
    private static String resultCode(Entry entry) {
        if (entry.reasonCode() != null && !entry.reasonCode().isBlank()) {
            return entry.reasonCode();
        }
        Object resultCode = entry.details().get(DETAIL_RESULT_CODE);
        return resultCode == null ? entry.eventType().name() : resultCode.toString();
    }

    /**
     * 构造带平面标签的审计明细。
     *
     * <p>明细里只出现网关侧派生的标签与标识：对端自报名称已由 {@code AgentIdentity} 归一化，
     * 凭据摘要走 {@code subjectDigest} 而不进明细，入站消息文本一概不进——审计需要的是
     * 「谁、何时、做了哪一类事」，而不是载荷副本。</p>
     */
    private String detailsJson(Entry entry) {
        AuditPlane plane = entry.eventType().plane();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put(AuditPlane.FIELD, plane.wireValue());
        details.put(FIELD_PEER, entry.identity().peerAgentName());
        details.put(FIELD_TRUST_TIER, entry.identity().trustTier().name());
        details.put(FIELD_TASK_ID, entry.taskContext().taskId());
        if (entry.reasonCode() != null && !entry.reasonCode().isBlank()) {
            details.put(FIELD_REASON, entry.reasonCode());
        }
        details.putAll(entry.details());
        try {
            return objectMapper.writeValueAsString(details);
        } catch (JsonProcessingException e) {
            // 明细是诊断信息，事件本身才是约束所在：序列化失败时退化成只含平面标签的明细，
            // 但绝不跳过落库——跳过就等于让一次可疑请求彻底无痕。
            return plane.detailsJson(DETAIL_RESULT_CODE, DETAILS_UNAVAILABLE);
        }
    }
}
