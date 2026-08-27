package com.ai.gateway.adapter.a2a;

import com.ai.gateway.domain.model.A2aTaskContext;
import com.ai.gateway.domain.model.AgentIdentity;
import com.ai.gateway.domain.model.RequestContext;
import com.ai.gateway.domain.model.TrustTier;
import io.a2a.spec.Task;
import io.a2a.spec.TaskState;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Acceptance tests for the rejecting retrieval strategy (design §3.4.1).
 *
 * <p>{@link A2aSelectionMode#STRUCTURED_ONLY} refuses free text at the policy enforcement point,
 * so this strategy is unreachable in normal operation. It is asserted anyway, because the whole
 * reason it exists is to keep a misconfiguration from turning into a {@code NullPointerException}
 * that only fires on one selection mode with one payload shape — a failure that would surface on
 * production traffic rather than at assembly time.</p>
 *
 * @author cmiracle@163.com
 */
class A2aRetrievalHandlerTest {

    private static final A2aTaskContext TASK =
            new A2aTaskContext("task-1", "ctx-1", "root-1", 0);

    private final A2aTaskStateMapper stateMapper = new A2aTaskStateMapper();

    @Test
    void theRejectingStrategyAnswersWithARejectedTaskBoundToTheIncomingTask() {
        Task task = A2aRetrievalHandler.rejecting(stateMapper).handle(request());

        // 拒绝必须表达在 Task 状态上并绑回同一个 taskId：换成抛异常，
        // 分发器只能回一个内部错误，对端便无从区分「不受理」与「网关坏了」。
        assertThat(task.getStatus().state()).isEqualTo(TaskState.REJECTED);
        assertThat(task.getId()).isEqualTo(TASK.taskId());
        assertThat(task.getContextId()).isEqualTo(TASK.contextId());
    }

    @Test
    void theRejectingStrategyCarriesNoReasonBackToThePeer() {
        Task task = A2aRetrievalHandler.rejecting(stateMapper).handle(request());

        // 档位信息属于网关侧配置；回给对端就等于告诉它「换一种载荷形态再试」。
        assertThat(task.getStatus().message()).isNotNull();
        assertThat(task.getArtifacts()).isEmpty();
    }

    @Test
    void aMissingStateMapperIsRejectedAtAssemblyTimeRatherThanAtFirstRequest() {
        assertThatThrownBy(() -> A2aRetrievalHandler.rejecting(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("stateMapper");
    }

    private static A2aRetrievalHandler.Request request() {
        return new A2aRetrievalHandler.Request(
                new RequestContext(Map.of(), Map.of(), Map.of(), "10.0.0.1"),
                TASK,
                new A2aPolicyEnforcementFilter.Decision(
                        A2aPolicyEnforcementFilter.Outcome.ADMITTED,
                        new AgentIdentity("peer", A2aPeerTrustRegistry.sha256("token"),
                                TrustTier.READ_ONLY),
                        A2aIdentityMode.ON_BEHALF_OF, null),
                "查订单",
                "req-1");
    }
}
