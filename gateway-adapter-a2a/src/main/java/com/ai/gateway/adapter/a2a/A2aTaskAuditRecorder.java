package com.ai.gateway.adapter.a2a;

import com.ai.gateway.domain.model.A2aTaskContext;
import com.ai.gateway.domain.model.AgentIdentity;
import com.ai.gateway.domain.model.AuditPlane;

import java.util.Map;
import java.util.Objects;

/**
 * A2A 平面的审计出口（设计 §3.8）。
 *
 * <p>做成适配层的<b>接口</b>而不是直接依赖 {@code AuditPort}，有两个理由：一是本模块不应决定
 * 审计事件如何序列化与落库；二是「终态审计先落库、再返回 Artifact」这条约束需要一个能失败的
 * 调用点——实现方抛出异常时，{@link A2aServerTransportAdapter} 会把结果降级为失败态而不是
 * 照常返回业务数据。若把审计写成一个不会失败的旁路调用，这条约束就无法被测试验证。</p>
 *
 * <p>本接口是函数式的，因此增加新的事件类型不需要改动接口，只需在
 * {@link EventType} 上追加取值（开闭原则）。出站委托事件复用同一个出口：出站与入站在
 * 「必须留下痕迹、且痕迹落不下去就不能当作成功」这一点上是同一条约束。</p>
 *
 * @author cmiracle@163.com
 * @since 0.1.0
 */
@FunctionalInterface
public interface A2aTaskAuditRecorder {

    /**
     * 记录一次 A2A 平面事件。
     *
     * @param entry 事件内容，恒不为 {@code null}
     * @throws RuntimeException 落库失败；调用方必须据此拒绝返回业务数据
     */
    void record(Entry entry);

    /**
     * 返回一个不做任何记录的实现。
     *
     * <p>仅用于单元测试与尚未接入审计基础设施的本地部署。生产装配必须提供真实实现，
     * 否则 A2A 平面会失去「谁在什么时候调用了什么」这唯一的事实来源。</p>
     *
     * @return 空实现
     */
    static A2aTaskAuditRecorder noop() {
        return entry -> {
        };
    }

    /**
     * 一条 A2A 平面审计事件。
     *
     * @param eventType   事件类型，不能为 {@code null}
     * @param taskContext 任务上下文，不能为 {@code null}
     * @param identity    对端身份，不能为 {@code null}
     * @param reasonCode  审计侧原因码，允许为 {@code null}；<b>不得写入任何对端可见的响应</b>
     * @param details     附加明细，{@code null} 视为空映射
     */
    record Entry(EventType eventType,
                 A2aTaskContext taskContext,
                 AgentIdentity identity,
                 String reasonCode,
                 Map<String, Object> details) {

        /**
         * 紧凑构造器：冻结明细映射。
         *
         * @param eventType   事件类型
         * @param taskContext 任务上下文
         * @param identity    对端身份
         * @param reasonCode  原因码
         * @param details     附加明细
         */
        public Entry {
            Objects.requireNonNull(eventType, "eventType must not be null");
            Objects.requireNonNull(taskContext, "taskContext must not be null");
            Objects.requireNonNull(identity, "identity must not be null");
            details = details == null ? Map.of() : Map.copyOf(details);
        }
    }

    /**
     * A2A 平面事件类型，取值与设计 §3.8 的表格一一对应。
     *
     * <p>每个取值自带所属平面，而不是让调用方在记录时另传一个平面参数：入站与出站的事件类型
     * 是互斥的，把平面做成类型的属性之后，「出站委托被打上入站标签」这种归因错误在类型层面
     * 就不可能发生。新增一个事件类型时也只需在这里追加一行，映射层无需改动（开闭原则）。</p>
     */
    enum EventType {

        /** 入站 Task 通过策略执行点。 */
        RECEIVED("a2a.task.received", AuditPlane.A2A_INBOUND),

        /** 入站 Task 被拒绝（注入命中 / 跳数超限 / 形态不受理 / 限流）。 */
        REJECTED("a2a.task.rejected", AuditPlane.A2A_INBOUND),

        /** 回传候选集或待确认摘要，等待对端补充输入。 */
        INPUT_REQUIRED("a2a.task.input_required", AuditPlane.A2A_INBOUND),

        /** 终态产出返回之前。 */
        COMPLETED("a2a.task.completed", AuditPlane.A2A_INBOUND),

        /** 网关作为客户端向远端 Agent 发起一次调用。 */
        DELEGATED("a2a.delegated", AuditPlane.A2A_OUTBOUND);

        private final String wireName;
        private final AuditPlane plane;

        EventType(String wireName, AuditPlane plane) {
            this.wireName = wireName;
            this.plane = plane;
        }

        /**
         * @return 审计记录里使用的事件类型名
         */
        public String wireName() {
            return wireName;
        }

        /**
         * @return 该事件类型所属的执行平面标签
         */
        public AuditPlane plane() {
            return plane;
        }
    }
}
