package com.ai.gateway.bootstrap.audit;

import com.ai.gateway.adapter.a2a.A2aTaskAuditRecorder;
import com.ai.gateway.domain.model.A2aTaskContext;
import com.ai.gateway.domain.model.AgentIdentity;
import com.ai.gateway.domain.model.AuditEvent;
import com.ai.gateway.domain.model.AuditPlane;
import com.ai.gateway.domain.model.TrustTier;
import com.ai.gateway.domain.port.AuditPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Acceptance tests for the A2A inbound audit mapping (design §3.8).
 *
 * @author cmiracle@163.com
 */
class A2aAuditPortRecorderTest {

    private static final Instant NOW = Instant.parse("2026-08-25T10:15:30Z");

    private static final A2aTaskContext TASK =
            new A2aTaskContext("task-1", "ctx-1", "root-1", 0);

    private static final AgentIdentity IDENTITY = new AgentIdentity(
            "peer-a", "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            TrustTier.READ_ONLY);

    private final RecordingAuditPort auditPort = new RecordingAuditPort();

    private final A2aAuditPortRecorder recorder = new A2aAuditPortRecorder(
            auditPort, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void aTerminalEventCarriesThePlaneTagSoTheQuerySideCanTellTheInboundPlaneApart() {
        recorder.record(entry(A2aTaskAuditRecorder.EventType.COMPLETED, null,
                Map.of("resultCode", "OK")));

        AuditEvent event = auditPort.single();
        assertThat(event.eventType()).isEqualTo("a2a.task.completed");
        assertThat(event.timestamp()).isEqualTo(NOW);
        assertThat(event.detailsJson())
                .contains("\"" + AuditPlane.FIELD + "\":\""
                        + AuditPlane.A2A_INBOUND.wireValue() + "\"")
                .contains("\"peerAgentName\":\"peer-a\"")
                .contains("\"trustTier\":\"READ_ONLY\"")
                .contains("\"taskId\":\"task-1\"");
    }

    @Test
    void theCredentialDigestGoesToTheSubjectFieldAndNeverIntoTheDetails() {
        recorder.record(entry(A2aTaskAuditRecorder.EventType.RECEIVED, null, Map.of()));

        AuditEvent event = auditPort.single();
        assertThat(event.subjectDigest()).isEqualTo(IDENTITY.peerDigest());
        // 明细是给人读的诊断信息，凭据摘要有固定的承载位置；两处都放等于多一个泄漏面。
        assertThat(event.detailsJson()).doesNotContain(IDENTITY.peerDigest());
    }

    @Test
    void theRootRequestIdIsWhatTiesTheWholeDelegationChainTogether() {
        recorder.record(entry(A2aTaskAuditRecorder.EventType.RECEIVED, null, Map.of()));

        // 用 rootRequestId 而不是 taskId 作为 requestId：跨 Agent 的一次委托会产生多个 taskId，
        // 只有根请求号能把它们串成一条可追的链。
        assertThat(auditPort.single().requestId()).isEqualTo("root-1");
    }

    @Test
    void theTenantIsAlwaysZeroBecauseAnInboundPeerHasNoTenantYet() {
        recorder.record(entry(A2aTaskAuditRecorder.EventType.RECEIVED, null, Map.of()));

        // 从 A2A 消息体里读一个 orgId 填进来是被禁止的——那等于让对端自选审计归属。
        assertThat(auditPort.single().orgId()).isZero();
    }

    @Test
    void aReasonCodeWinsOverTheTransportSuppliedResultCode() {
        // 拒绝事件与终态事件不会同时出现，因此这不是取舍而是取其一：
        // 审计查询侧需要一个恒定位置来回答「这条请求最后怎么了」。
        recorder.record(entry(A2aTaskAuditRecorder.EventType.REJECTED, "HOP_LIMIT_EXCEEDED",
                Map.of("resultCode", "OK")));

        AuditEvent event = auditPort.single();
        assertThat(event.resultCode()).isEqualTo("HOP_LIMIT_EXCEEDED");
        assertThat(event.detailsJson()).contains("\"reasonCode\":\"HOP_LIMIT_EXCEEDED\"");
    }

    @Test
    void aBlankReasonCodeFallsBackToTheTransportSuppliedResultCode() {
        recorder.record(entry(A2aTaskAuditRecorder.EventType.COMPLETED, "   ",
                Map.of("resultCode", "AUDIT_UNAVAILABLE")));

        assertThat(auditPort.single().resultCode()).isEqualTo("AUDIT_UNAVAILABLE");
    }

    @Test
    void anEventWithNeitherStillGetsAStableResultCode() {
        recorder.record(entry(A2aTaskAuditRecorder.EventType.RECEIVED, null, Map.of()));

        // 空结果码会让「按结果码聚合」的审计查询悄悄漏掉这一类事件。
        assertThat(auditPort.single().resultCode()).isEqualTo("RECEIVED");
    }

    @Test
    void anUnserializableDetailLeavesAThinnerRecordRatherThanNoRecordAtAll() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("offending", new Object() {
            @SuppressWarnings("unused")
            public String getBoom() {
                throw new IllegalStateException("not serializable");
            }
        });

        recorder.record(entry(A2aTaskAuditRecorder.EventType.REJECTED, null, details));

        // 明细是诊断信息，事件本身才是约束所在：跳过落库就等于让一次可疑请求彻底无痕。
        AuditEvent event = auditPort.single();
        assertThat(event.detailsJson())
                .contains(AuditPlane.A2A_INBOUND.wireValue())
                .contains("DETAILS_UNAVAILABLE");
    }

    @Test
    void aFailingAuditSinkPropagatesSoTheTransportCanWithholdTheBusinessResult() {
        auditPort.failing = true;

        // 在这里 catch 一次，「审计没落库就不返回业务产物」整条约束就会静默失效，
        // 而失效形态是「一切照常返回，只是没有痕迹」——最难被发现的那种。
        assertThatThrownBy(() -> recorder.record(
                entry(A2aTaskAuditRecorder.EventType.COMPLETED, null, Map.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("audit sink unavailable");
    }

    @Test
    void eachEventGetsItsOwnIdentity() {
        recorder.record(entry(A2aTaskAuditRecorder.EventType.RECEIVED, null, Map.of()));
        recorder.record(entry(A2aTaskAuditRecorder.EventType.COMPLETED, null, Map.of()));

        assertThat(auditPort.events).hasSize(2);
        assertThat(auditPort.events.get(0).eventId())
                .isNotBlank()
                .isNotEqualTo(auditPort.events.get(1).eventId());
    }

    @Test
    void anOutboundDelegationIsTaggedWithTheOutboundPlane() {
        // 平面标签取自事件类型自身，映射层不做入站/出站判定：判定一次就意味着可以判定错，
        // 而「出站委托被记成入站事件」会让远端 Agent 的故障表现为本网关暴露面故障。
        recorder.record(entry(A2aTaskAuditRecorder.EventType.DELEGATED, null,
                Map.of("skillId", "skill-1")));

        AuditEvent event = auditPort.single();
        assertThat(event.eventType()).isEqualTo("a2a.delegated");
        assertThat(event.detailsJson())
                .contains("\"" + AuditPlane.FIELD + "\":\""
                        + AuditPlane.A2A_OUTBOUND.wireValue() + "\"")
                .contains("\"skillId\":\"skill-1\"");
    }

    @Test
    void everyEventTypeHasAWireNameAndAPlaneAndNoTwoShareAWireName() {
        assertThat(A2aTaskAuditRecorder.EventType.values())
                .allSatisfy(type -> {
                    assertThat(type.wireName()).startsWith("a2a.");
                    assertThat(type.plane()).isIn(
                            AuditPlane.A2A_INBOUND, AuditPlane.A2A_OUTBOUND);
                })
                .extracting(A2aTaskAuditRecorder.EventType::wireName)
                .doesNotHaveDuplicates();
    }

    private static A2aTaskAuditRecorder.Entry entry(A2aTaskAuditRecorder.EventType eventType,
                                                    String reasonCode,
                                                    Map<String, Object> details) {
        return new A2aTaskAuditRecorder.Entry(eventType, TASK, IDENTITY, reasonCode, details);
    }

    /** 只收集事件的审计出口；可切换成「落库失败」以验证失效关闭。 */
    private static final class RecordingAuditPort implements AuditPort {

        private final List<AuditEvent> events = new ArrayList<>();
        private boolean failing;

        @Override
        public void recordAccepted(String requestId, String subjectDigest, long orgId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void recordStarted(com.ai.gateway.domain.model.ExecutionAuditContext context) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void recordTerminal(com.ai.gateway.domain.model.ExecutionAuditContext context,
                                   String resultCode, long durationMs, String detailsJson) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void recordEvent(AuditEvent event) {
            if (failing) {
                throw new IllegalStateException("audit sink unavailable");
            }
            events.add(event);
        }

        AuditEvent single() {
            assertThat(events).hasSize(1);
            return events.get(0);
        }
    }
}
