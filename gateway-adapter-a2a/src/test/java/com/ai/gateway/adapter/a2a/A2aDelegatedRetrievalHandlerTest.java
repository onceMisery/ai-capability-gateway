package com.ai.gateway.adapter.a2a;

import com.ai.gateway.application.agent.AgentCapabilityResolver;
import com.ai.gateway.application.agent.AgentHostConnector;
import com.ai.gateway.domain.model.A2aTaskContext;
import com.ai.gateway.domain.model.AgentIdentity;
import com.ai.gateway.domain.model.RequestContext;
import com.ai.gateway.domain.model.TrustTier;
import com.ai.gateway.domain.service.Sha256Digest;
import io.a2a.spec.DataPart;
import io.a2a.spec.Part;
import io.a2a.spec.Task;
import io.a2a.spec.TaskState;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Acceptance tests for the default selection mode's first hop (design §3.4.1).
 *
 * <p>This mode's whole point is that the gateway hands back an already-authorized candidate
 * set and lets the peer's own model choose — so the tests are about what crosses the boundary,
 * not about the retrieval itself. Three properties carry the security argument: the hand-back
 * contains short aliases only (never a real capability id, protocol binding or schema class),
 * "nothing matched" and "you may not see it" are indistinguishable, and an empty candidate set
 * is a terminal failure rather than an {@code INPUT_REQUIRED} the peer would retry forever.</p>
 *
 * @author cmiracle@163.com
 */
class A2aDelegatedRetrievalHandlerTest {

    private static final Instant NOW = Instant.parse("2026-08-25T10:15:30Z");
    private static final A2aTaskContext TASK = new A2aTaskContext(
            "task-1", "ctx-1", "root-1", 0);

    private final A2aTaskStateMapper stateMapper =
            new A2aTaskStateMapper(Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void aResolvedCandidateSetIsHandedBackAsInputRequiredSoThePeerCanChoose() {
        AgentHostConnector connector = resolving(resolution(
                candidate("cap_a1b2c3", "订单查询", "按单号查询订单状态"),
                candidate("cap_d4e5f6", "物流查询", "查询运单轨迹")));

        Task task = handler(connector, 5).handle(request());

        assertThat(task.getStatus().state()).isEqualTo(TaskState.INPUT_REQUIRED);
        assertThat(candidates(task)).extracting(A2aTaskRequest.FIELD_TOOL_REF)
                .containsExactly("cap_a1b2c3", "cap_d4e5f6");
    }

    @Test
    void theHandBackNeverCarriesTheSchemaClassOrAnyOtherInternalClassification() {
        AgentHostConnector connector = resolving(resolution(
                candidate("cap_a1b2c3", "订单查询", "按单号查询订单状态")));

        Task task = handler(connector, 5).handle(request());

        // schemaClass 描述的是网关如何加载入参 Schema，对选择决策毫无帮助，只会透露实现细节。
        assertThat(candidates(task).get(0)).containsOnlyKeys(
                A2aTaskRequest.FIELD_TOOL_REF, "name", "purpose", "executionMode",
                A2aDelegatedRetrievalHandler.FIELD_ARGUMENT_CONTRACT);
    }

    @Test
    void theHandBackDeclaresTheSecondHopEnvelopeSoThePeerNeedNotInferFieldNames() {
        AgentHostConnector connector = resolving(resolution(
                candidate("cap_a1b2c3", "订单查询", "按单号查询订单状态")));

        Task task = handler(connector, 5).handle(request());

        // 第二跳的信封形态与具体能力无关，因此与各候选自带的 argumentContract 分开声明。
        assertThat(payload(task)).extractingByKey(
                        A2aDelegatedRetrievalHandler.FIELD_ARGUMENT_CONTRACT)
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsOnlyKeys(A2aTaskRequest.FIELD_TOOL_REF,
                        A2aTaskRequest.FIELD_ARGUMENTS);
    }

    @Test
    void aCandidateWithoutAnArgumentContractStillCarriesTheFieldAsAnEmptyObject() {
        AgentHostConnector connector = resolving(resolution(
                new AgentCapabilityResolver.Candidate("cap_a1b2c3", "订单查询",
                        "按单号查询订单状态", null, null, null)));

        Task task = handler(connector, 5).handle(request());

        // 缺字段会让对端的解析代码分叉；空对象是「本能力不需要入参」的确定性表达。
        assertThat(candidates(task).get(0))
                .containsEntry(A2aDelegatedRetrievalHandler.FIELD_ARGUMENT_CONTRACT, Map.of());
        assertThat(candidates(task).get(0)).doesNotContainKey("executionMode");
    }

    @Test
    void theCandidateSetExpiryIsHandedBackSoThePeerKnowsWhenTheHandleDies() {
        AgentHostConnector connector = resolving(new AgentCapabilityResolver.Resolution(
                AgentCapabilityResolver.Status.RESOLVED, null, 7L, 3L,
                List.of(candidate("cap_a1b2c3", "订单查询", "按单号查询订单状态")),
                null, NOW.plusSeconds(300)));

        Task task = handler(connector, 5).handle(request());

        assertThat(payload(task)).containsEntry(
                A2aDelegatedRetrievalHandler.FIELD_EXPIRES_AT, NOW.plusSeconds(300).toString());
    }

    @Test
    void anEmptyCandidateSetFailsTerminallyInsteadOfAskingForInputWithNoOptions() {
        AgentHostConnector connector = resolving(resolution());

        Task task = handler(connector, 5).handle(request());

        // 一个「需要补充输入」却没给出任何可选项的响应，会让对端反复重试同一条永远无解的路径。
        assertThat(task.getStatus().state()).isEqualTo(TaskState.FAILED);
        assertThat(errorCode(task)).isEqualTo(A2aDelegatedRetrievalHandler.NO_CAPABILITY_CODE);
    }

    @Test
    void anAuthorizationFailureAndAnEmptyMatchAreIndistinguishableToThePeer() {
        // 授权过滤发生在检索之前，因此「无权限」在这一层表现为空候选集——
        // 与「确实没有匹配」共用同一错误码，区分开来就等于让对端可以探测能力面的存在与否。
        Task unauthorized = handler(resolving(resolution()), 5).handle(request());
        Task noMatch = handler(resolving(resolution()), 5).handle(request());

        assertThat(errorCode(unauthorized)).isEqualTo(errorCode(noMatch));
    }

    @Test
    void aResolverErrorCodeIsForwardedAsAStableCodeWithoutProviderWording() {
        AgentHostConnector connector = resolving(new AgentCapabilityResolver.Resolution(
                AgentCapabilityResolver.Status.ERROR, "RESOLVE_TIMEOUT", 0L, 0L,
                List.of(), null, null));

        Task task = handler(connector, 5).handle(request());

        assertThat(task.getStatus().state()).isEqualTo(TaskState.FAILED);
        assertThat(errorCode(task)).isEqualTo("RESOLVE_TIMEOUT");
    }

    @Test
    void theAgentTurnIsKeyedByTheA2aTaskIdSoTheSecondHopCanRedeemTheHandle() {
        AgentHostConnector connector = resolving(resolution(
                candidate("cap_a1b2c3", "订单查询", "按单号查询订单状态")));

        handler(connector, 4).handle(request());

        // 候选句柄的生命周期复用既有回合记忆：超出该 turn 或超时即失效，
        // 因此 A2A 侧不需要另建一套过期机制。
        verify(connector).resolve(any(RequestContext.class), eq("task-1"),
                eq("root-1"), eq("查询订单"), eq(4));
    }

    @Test
    void aNonPositiveTopKIsRejectedAtWiringTimeRatherThanReturningNoCandidates() {
        AgentHostConnector connector = mock(AgentHostConnector.class);

        assertThatThrownBy(() -> new A2aDelegatedRetrievalHandler(connector, stateMapper, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("topK");
    }

    private A2aDelegatedRetrievalHandler handler(AgentHostConnector connector, int topK) {
        return new A2aDelegatedRetrievalHandler(connector, stateMapper, topK);
    }

    private static AgentHostConnector resolving(AgentCapabilityResolver.Resolution resolution) {
        AgentHostConnector connector = mock(AgentHostConnector.class);
        when(connector.resolve(any(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(new AgentHostConnector.ResolveResult(resolution, null));
        return connector;
    }

    private static AgentCapabilityResolver.Resolution resolution(
            AgentCapabilityResolver.Candidate... candidates) {
        return new AgentCapabilityResolver.Resolution(
                AgentCapabilityResolver.Status.RESOLVED, null, 7L, 3L,
                List.of(candidates), null, null);
    }

    private static AgentCapabilityResolver.Candidate candidate(
            String toolRef, String displayName, String purpose) {
        return new AgentCapabilityResolver.Candidate(toolRef, displayName, purpose,
                null, Map.of("orderId", "string"), "READ_ONLY");
    }

    private static A2aRetrievalHandler.Request request() {
        return new A2aRetrievalHandler.Request(RequestContext.empty(), TASK,
                new A2aPolicyEnforcementFilter.Decision(
                        A2aPolicyEnforcementFilter.Outcome.ADMITTED,
                        new AgentIdentity("orchestrator",
                                Sha256Digest.sha256Hex("orchestrator"), TrustTier.READ_ONLY),
                        A2aIdentityMode.ON_BEHALF_OF, null),
                "查询订单", "root-1");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> candidates(Task task) {
        return new ArrayList<>((List<Map<String, Object>>)
                payload(task).get(A2aDelegatedRetrievalHandler.FIELD_CANDIDATES));
    }

    private static Map<String, Object> payload(Task task) {
        return dataOf(task.getStatus().message().getParts());
    }

    private static String errorCode(Task task) {
        // 非完成态没有业务产物：说明与错误码都在 status.message 里，
        // 造一个空 Artifact 会被对端读成「有产物但内容为空」。
        return String.valueOf(payload(task).get("errorCode"));
    }

    private static Map<String, Object> dataOf(List<Part<?>> parts) {
        return parts.stream()
                .filter(DataPart.class::isInstance)
                .map(part -> ((DataPart) part).getData())
                .findFirst()
                .orElseThrow(() -> new AssertionError("no DataPart present"));
    }
}
