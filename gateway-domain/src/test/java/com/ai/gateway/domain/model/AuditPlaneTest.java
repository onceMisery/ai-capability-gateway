package com.ai.gateway.domain.model;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Wire contract of the audit-plane label.
 *
 * <p>The plane travels into audit rows and monitoring labels, so its textual form is an
 * external contract: renaming an enum constant must not change it, values must stay
 * distinct, and the hand-assembled details JSON must stay well formed even when a field
 * value arrives from a protocol adapter.</p>
 */
class AuditPlaneTest {

    /** SOH: a control character that must be emitted as a unicode escape. */
    private static final String CONTROL_CHAR = String.valueOf((char) 1);

    @Test
    void everyPlaneHasADistinctStableWireValue() {
        assertThat(Arrays.stream(AuditPlane.values()).map(AuditPlane::wireValue))
                .doesNotHaveDuplicates()
                .allSatisfy(value -> assertThat(value).matches("[a-z0-9]+(-[a-z0-9]+)*"));
    }

    @Test
    void planeOnlyDetailsCarryTheAgreedFieldName() {
        assertThat(AuditPlane.FIELD).isEqualTo("plane");
        assertThat(AuditPlane.GATEWAY_NL.detailsJson())
                .isEqualTo("{\"plane\":\"gateway-nl\"}");
        assertThat(AuditPlane.GATEWAY_NL_DIAGNOSTIC.detailsJson())
                .isEqualTo("{\"plane\":\"gateway-nl-diagnostic\"}");
    }

    @Test
    void singleFieldDetailsKeepThePlaneFirst() {
        assertThat(AuditPlane.MCP.detailsJson("reason", "payload_budget_exceeded"))
                .isEqualTo("{\"plane\":\"mcp\",\"reason\":\"payload_budget_exceeded\"}");
    }

    /** Adapter-supplied values must not be able to break out of their JSON string. */
    @Test
    void fieldValuesAreEscaped() {
        assertThat(AuditPlane.AGENT_HOST.detailsJson("protocolStatus", "BAD\",\"injected\":\"x"))
                .isEqualTo("{\"plane\":\"agent-host\","
                        + "\"protocolStatus\":\"BAD\\\",\\\"injected\\\":\\\"x\"}");
        assertThat(AuditPlane.AGENT_HOST.detailsJson("protocolStatus", "line\nbreak\tand\\slash"))
                .isEqualTo("{\"plane\":\"agent-host\","
                        + "\"protocolStatus\":\"line\\nbreak\\tand\\\\slash\"}");
        assertThat(AuditPlane.AGENT_HOST.detailsJson("protocolStatus", "ctrl" + CONTROL_CHAR))
                .isEqualTo("{\"plane\":\"agent-host\",\"protocolStatus\":\"ctrl\\u0001\"}");
    }

    @Test
    void missingFieldOrValueDegradesToThePlaneOnlyForm() {
        assertThat(AuditPlane.MCP.detailsJson("reason", null))
                .isEqualTo(AuditPlane.MCP.detailsJson());
        assertThat(AuditPlane.MCP.detailsJson("  ", "ignored"))
                .isEqualTo(AuditPlane.MCP.detailsJson());
    }

    @Test
    void rawFieldsAreMergedAndAcceptBothWrappedAndBareForms() {
        assertThat(AuditPlane.STRUCTURED.detailsJsonWithRawFields("\"snapshotVersion\":7"))
                .isEqualTo("{\"plane\":\"structured\",\"snapshotVersion\":7}");
        // 既有调用点写的是完整对象字面量，迁移时不必手工去掉花括号。
        assertThat(AuditPlane.STRUCTURED.detailsJsonWithRawFields("{\"snapshotVersion\":7}"))
                .isEqualTo("{\"plane\":\"structured\",\"snapshotVersion\":7}");
        assertThat(AuditPlane.STRUCTURED.detailsJsonWithRawFields("{}"))
                .isEqualTo(AuditPlane.STRUCTURED.detailsJson());
        assertThat(AuditPlane.STRUCTURED.detailsJsonWithRawFields(null))
                .isEqualTo(AuditPlane.STRUCTURED.detailsJson());
    }

    @Test
    void executionContextDefaultsToTheStructuredPlaneAndDerivesOthers() {
        ExecutionAuditContext context = new ExecutionAuditContext(
                "req-1", null, "digest", 7L, "order.query", "1.0.0", "manifest-digest", 3L);

        assertThat(context.plane()).isEqualTo(AuditPlane.STRUCTURED);
        ExecutionAuditContext mcp = context.withPlane(AuditPlane.MCP);
        assertThat(mcp.plane()).isEqualTo(AuditPlane.MCP);
        // 派生只替换平面：其余字段是执行事实，必须逐字保留。
        assertThat(mcp.withPlane(AuditPlane.STRUCTURED)).isEqualTo(context);
        assertThatThrownBy(() -> context.withPlane(null))
                .isInstanceOf(NullPointerException.class);
    }

    /**
     * Persisted plane values must round-trip, and anything unrecognised must degrade to
     * {@code UNKNOWN} rather than fail the read.
     */
    @Test
    void unrecognisedWireValuesDegradeToUnknownInsteadOfThrowing() {
        assertThat(AuditPlane.fromWireValue("mcp")).isEqualTo(AuditPlane.MCP);
        assertThat(AuditPlane.fromWireValue("gateway-nl-diagnostic"))
                .isEqualTo(AuditPlane.GATEWAY_NL_DIAGNOSTIC);
        // 兼容枚举常量名，便于配置与测试书写。
        assertThat(AuditPlane.fromWireValue("A2A_OUTBOUND")).isEqualTo(AuditPlane.A2A_OUTBOUND);
        assertThat(AuditPlane.fromWireValue(" agent-host ")).isEqualTo(AuditPlane.AGENT_HOST);
        // 迁移之前入库的行没有平面列，读回时是 null；灰度期回滚可能读到未知取值。
        assertThat(AuditPlane.fromWireValue(null)).isEqualTo(AuditPlane.UNKNOWN);
        assertThat(AuditPlane.fromWireValue("  ")).isEqualTo(AuditPlane.UNKNOWN);
        assertThat(AuditPlane.fromWireValue("a-plane-from-the-future"))
                .isEqualTo(AuditPlane.UNKNOWN);
        // 每个平面都能由自己的线上取值还原，否则 wireValue 就不是一个可往返的契约。
        for (AuditPlane plane : AuditPlane.values()) {
            assertThat(AuditPlane.fromWireValue(plane.wireValue())).isEqualTo(plane);
        }
    }

    /**
     * Confirm/Cancel is a separate request, so the terminal audit context must take the
     * plane from the frozen record — never from the confirming request.
     */
    @Test
    void operationContextTakesThePlaneFrozenAtPrepare() {
        assertThat(ExecutionAuditContext.forOperation(record(AuditPlane.MCP)).plane())
                .isEqualTo(AuditPlane.MCP);
        // 未记录平面时保持 unknown：默认成 structured 会把 MCP 写操作错误计入结构化直调。
        assertThat(ExecutionAuditContext.forOperation(record(null)).plane())
                .isEqualTo(AuditPlane.UNKNOWN);
    }

    private static OperationRecord record(AuditPlane plane) {
        return new OperationRecord("op-1", OperationState.PREPARED, "digest", 7L,
                "order.update", "1.0.0", "manifest-digest", 3L, "encrypted", "args-digest",
                "idem-1", "policy-1", null, java.time.Instant.now().plusSeconds(300), 0L,
                plane);
    }
}
