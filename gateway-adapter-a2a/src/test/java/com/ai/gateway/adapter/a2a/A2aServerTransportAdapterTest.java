package com.ai.gateway.adapter.a2a;

import com.ai.gateway.application.agent.AgentCardProjection;
import com.ai.gateway.application.agent.AgentCardProjectionService;
import com.ai.gateway.application.agent.AgentHostConnector;
import com.ai.gateway.application.agent.AgentModelResultMapper.ModelResult;
import com.ai.gateway.application.resilience.RateLimiterManager;
import com.ai.gateway.domain.model.A2aTaskContext;
import com.ai.gateway.domain.model.AgentIdentity;
import com.ai.gateway.domain.model.AuditPlane;
import com.ai.gateway.domain.model.RequestContext;
import com.ai.gateway.domain.model.TrustTier;
import com.ai.gateway.domain.port.RateLimiterPort;
import com.ai.gateway.domain.port.TelemetryPort;
import io.a2a.spec.DataPart;
import io.a2a.spec.Message;
import io.a2a.spec.Part;
import io.a2a.spec.Task;
import io.a2a.spec.TaskState;
import io.a2a.spec.TextPart;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Acceptance tests for the inbound A2A transport adapter (design §3.4, §3.5, §3.9).
 *
 * <p>The adapter orchestrates and does not decide, so these tests are about ordering and
 * delegation rather than about rules. Four properties would each be invisible to a test that
 * only checked the returned {@link TaskState}: a terminal audit is written <em>before</em> the
 * artifact is handed back (and a failed write costs the artifact), a refusal is audited with a
 * reason the peer never sees, the write path is only reachable by a peer that actually has a
 * confirmation channel, and a retried second hop derives the same idempotency key so a
 * redelivery cannot become a second write.</p>
 *
 * @author cmiracle@163.com
 */
class A2aServerTransportAdapterTest {

    private static final Instant NOW = Instant.parse("2026-08-25T10:15:30Z");
    private static final A2aTaskContext TASK = new A2aTaskContext(
            "task-1", "ctx-1", "root-1", 0);
    private static final String TRUSTED_TOKEN = "trusted-peer-token";
    private static final AgentCardProjection PUBLIC_CARD = new AgentCardProjection(
            "capability-gateway", "受治理的企业能力执行平面",
            "https://gateway.internal/a2a", "0.1.0", true,
            List.of("text/plain", "application/json"),
            List.of("text/plain", "application/json"), List.of());

    private final A2aTaskStateMapper stateMapper =
            new A2aTaskStateMapper(Clock.fixed(NOW, ZoneOffset.UTC));
    private final RecordingAuditRecorder audit = new RecordingAuditRecorder();

    @Test
    void aFreeTextTaskFromAnAdmittedPeerReachesTheConfiguredRetrievalStrategy() {
        A2aRetrievalHandler retrieval = handing(stateMapper.toCandidateTask(
                TASK, Map.of("candidates", List.of())));

        Task task = adapter(retrieval, mock(AgentHostConnector.class))
                .handleTask(bearer(TRUSTED_TOKEN), TASK, text("查询订单"));

        // 选择归属是一个可替换的装配决定，传输层不认识任何一档的内部差异。
        assertThat(task.getStatus().state()).isEqualTo(TaskState.INPUT_REQUIRED);
        verify(retrieval).handle(any(A2aRetrievalHandler.Request.class));
    }

    @Test
    void theRetrievalStrategyReceivesTheRootRequestIdSoARetriedHopHitsTheSameMemo() {
        A2aRetrievalHandler retrieval = handing(stateMapper.toCandidateTask(
                TASK, Map.of("candidates", List.of())));

        adapter(retrieval, mock(AgentHostConnector.class))
                .handleTask(bearer(TRUSTED_TOKEN), TASK, text("查询订单"));

        A2aRetrievalHandler.Request captured = capture(retrieval);
        assertThat(captured.requestId()).isEqualTo("root-1");
        assertThat(captured.taskContext().taskId()).isEqualTo("task-1");
        assertThat(captured.query()).isEqualTo("查询订单");
    }

    @Test
    void anUnauthenticatedTaskAsksForAStrongerIdentityAndNeverReachesTheChain() {
        A2aRetrievalHandler retrieval = handing(stateMapper.rejected(TASK));

        Task task = adapter(retrieval, mock(AgentHostConnector.class))
                .handleTask(RequestContext.empty(), TASK, text("查询订单"));

        assertThat(task.getStatus().state()).isEqualTo(TaskState.AUTH_REQUIRED);
        verify(retrieval, never()).handle(any());
    }

    @Test
    void aRefusalIsAuditedWithAReasonCodeThatTheReplyItselfNeverCarries() {
        Task task = adapter(handing(stateMapper.rejected(TASK)), mock(AgentHostConnector.class))
                .handleTask(RequestContext.empty(), TASK, text("查询订单"));

        // 原因码只进审计：让它出境等于给对端一个可枚举的失败原因表。
        assertThat(audit.reasonCodes()).containsExactly(
                A2aPolicyEnforcementFilter.REASON_UNAUTHENTICATED);
        assertThat(payload(task).toString()).doesNotContain(
                A2aPolicyEnforcementFilter.REASON_UNAUTHENTICATED);
    }

    @Test
    void aRefusedTaskIsNotEvenRecordedAsReceivedBecauseItWasNotAdmitted() {
        adapter(handing(stateMapper.rejected(TASK)), mock(AgentHostConnector.class))
                .handleTask(RequestContext.empty(), TASK, text("查询订单"));

        assertThat(audit.eventTypes()).containsExactly(
                A2aTaskAuditRecorder.EventType.REJECTED);
    }

    @Test
    void anAdmittedTaskIsRecordedAsReceivedBeforeAnythingIsExecuted() {
        adapter(handing(stateMapper.toCandidateTask(TASK, Map.of("candidates", List.of()))),
                mock(AgentHostConnector.class))
                .handleTask(bearer(TRUSTED_TOKEN), TASK, text("查询订单"));

        assertThat(audit.eventTypes()).containsExactly(
                A2aTaskAuditRecorder.EventType.RECEIVED,
                A2aTaskAuditRecorder.EventType.INPUT_REQUIRED);
        assertThat(audit.entries().get(0).details())
                .containsEntry("intent", A2aTaskRequest.Kind.RETRIEVAL.name())
                .containsEntry("delegationDepth", 0);
    }

    @Test
    void aPayloadWithNoRecognisableIntentIsRefusedRatherThanDispatchedOnAGuess() {
        A2aRetrievalHandler retrieval = handing(stateMapper.rejected(TASK));

        Task task = adapter(retrieval, mock(AgentHostConnector.class)).handleTask(
                bearer(TRUSTED_TOKEN), TASK, data(Map.of("unrelated", "value")));

        assertThat(task.getStatus().state()).isEqualTo(TaskState.REJECTED);
        verify(retrieval, never()).handle(any());
        assertThat(audit.reasonCodes())
                .containsExactly(A2aServerTransportAdapter.REASON_MALFORMED);
    }

    @Test
    void aSecondHopRedeemsTheHandleThroughTheExecutionChainOnTheInboundPlane() {
        AgentHostConnector connector = calling(new ModelResult(
                ModelResult.Status.COMPLETED, Map.of("orderNo", "SO-1"),
                null, null, null, null));

        Task task = adapter(handing(stateMapper.rejected(TASK)), connector).handleTask(
                bearer(TRUSTED_TOKEN), TASK, invocation("cap_a1b2c3", Map.of("orderId", "SO-1")));

        assertThat(task.getStatus().state()).isEqualTo(TaskState.COMPLETED);
        // 平面只决定审计与埋点归属，与调用策略正交；入站 A2A 的成本必须记在自己的平面上。
        verify(connector).call(any(RequestContext.class), eq("task-1"), eq("root-1"),
                eq("cap_a1b2c3"), eq(Map.of("orderId", "SO-1")), eq("zh-CN"),
                anyString(), any(AgentHostConnector.CallPolicy.class),
                eq(AuditPlane.A2A_INBOUND));
    }

    @Test
    void aRedeliveredSecondHopDerivesTheSameIdempotencyKeySoItCannotWriteTwice() {
        AgentHostConnector first = calling(new ModelResult(
                ModelResult.Status.COMPLETED, Map.of(), null, null, null, null));
        AgentHostConnector second = calling(new ModelResult(
                ModelResult.Status.COMPLETED, Map.of(), null, null, null, null));

        adapter(handing(stateMapper.rejected(TASK)), first).handleTask(bearer(TRUSTED_TOKEN),
                TASK, invocation("cap_a1b2c3", Map.of("orderId", "SO-1")));
        adapter(handing(stateMapper.rejected(TASK)), second).handleTask(bearer(TRUSTED_TOKEN),
                TASK, invocation("cap_a1b2c3", Map.of("orderId", "SO-1")));

        // A2A 的重传会带回同一个 taskId；随机幂等键会把一次重试变成第二次写。
        assertThat(idempotencyKey(first)).isEqualTo(idempotencyKey(second));
    }

    @Test
    void aDifferentArgumentSetDerivesADifferentKeySoItIsNotCoalescedAway() {
        AgentHostConnector first = calling(new ModelResult(
                ModelResult.Status.COMPLETED, Map.of(), null, null, null, null));
        AgentHostConnector second = calling(new ModelResult(
                ModelResult.Status.COMPLETED, Map.of(), null, null, null, null));

        adapter(handing(stateMapper.rejected(TASK)), first).handleTask(bearer(TRUSTED_TOKEN),
                TASK, invocation("cap_a1b2c3", Map.of("orderId", "SO-1")));
        adapter(handing(stateMapper.rejected(TASK)), second).handleTask(bearer(TRUSTED_TOKEN),
                TASK, invocation("cap_a1b2c3", Map.of("orderId", "SO-2")));

        assertThat(idempotencyKey(first)).isNotEqualTo(idempotencyKey(second));
    }

    @Test
    void theIdempotencyKeyIsADigestSoNoArgumentValueEntersAnyKeySpace() {
        AgentHostConnector connector = calling(new ModelResult(
                ModelResult.Status.COMPLETED, Map.of(), null, null, null, null));

        adapter(handing(stateMapper.rejected(TASK)), connector).handleTask(bearer(TRUSTED_TOKEN),
                TASK, invocation("cap_a1b2c3", Map.of("idCard", "310101199001010011")));

        assertThat(idempotencyKey(connector))
                .doesNotContain("310101199001010011")
                .matches("[0-9a-f]{64}");
    }

    @Test
    void aTrustedPeerReachesTheWritePathWithConfirmationAllowed() {
        AgentHostConnector connector = calling(new ModelResult(
                ModelResult.Status.CONFIRMATION_REQUIRED, Map.of(), null, null,
                "op-7", NOW.plusSeconds(300)));

        Task task = adapter(handing(stateMapper.rejected(TASK)), connector).handleTask(
                bearer(TRUSTED_TOKEN), TASK, invocation("cap_a1b2c3", Map.of()));

        assertThat(task.getStatus().state()).isEqualTo(TaskState.INPUT_REQUIRED);
        assertThat(payload(task)).containsEntry("operationId", "op-7");
        verify(connector).call(any(), anyString(), anyString(), anyString(), any(), anyString(),
                anyString(), eq(AgentHostConnector.CallPolicy.HOST_CONFIRMATION), any());
    }

    @Test
    void theConfirmationTokenNeverAppearsInTheReplyOnlyTheOperationId() {
        AgentHostConnector connector = mock(AgentHostConnector.class);
        when(connector.call(any(), anyString(), anyString(), anyString(), any(), anyString(),
                anyString(), any(), any()))
                .thenReturn(new AgentHostConnector.CallResult(new ModelResult(
                        ModelResult.Status.CONFIRMATION_REQUIRED, Map.of(), null, null,
                        "op-7", NOW.plusSeconds(300)), null, "host-only-token"));

        Task task = adapter(handing(stateMapper.rejected(TASK)), connector).handleTask(
                bearer(TRUSTED_TOKEN), TASK, invocation("cap_a1b2c3", Map.of()));

        // 令牌只存在于网关侧与受信 Host 的私有状态里，任何 A2A 报文都不得携带它。
        assertThat(task.toString()).doesNotContain("host-only-token");
        assertThat(payload(task).toString()).doesNotContain("host-only-token");
    }

    @Test
    void anUntrustedPeerIsHeldToReadOnlySoAWritePrepareIsNeverEvenAttempted() {
        AgentHostConnector connector = calling(new ModelResult(
                ModelResult.Status.COMPLETED, Map.of(), null, null, null, null));

        // 空注册表：已认证但未注册的 peer 恒为只读，这不是降级配置而是期望的初始状态。
        adapter(A2aPeerTrustRegistry.disabled(), handing(stateMapper.rejected(TASK)), connector,
                projectionService()).handleTask(
                bearer("unregistered-token"), TASK, invocation("cap_a1b2c3", Map.of()));

        verify(connector).call(any(), anyString(), anyString(), anyString(), any(), anyString(),
                anyString(), eq(AgentHostConnector.CallPolicy.READ_ONLY), any());
    }

    @Test
    void aServiceAccountPeerIsHeldToReadOnlyOnTheWholeInvocationPath() {
        AgentHostConnector connector = calling(new ModelResult(
                ModelResult.Status.COMPLETED, Map.of(), null, null, null, null));

        adapter(registry(serviceAccountProfile()), handing(stateMapper.rejected(TASK)),
                connector, projectionService()).handleTask(
                bearer(TRUSTED_TOKEN), TASK, invocation("cap_a1b2c3", Map.of()));

        // 服务账号背后没有最终用户，没人能承担确认动作，因此写路径不对它开放。
        verify(connector).call(any(), anyString(), anyString(), anyString(), any(), anyString(),
                anyString(), eq(AgentHostConnector.CallPolicy.READ_ONLY), any());
    }

    @Test
    void aServiceAccountCannotEvenBeRegisteredWithTheConfirmationTier() {
        // 「服务账号 + 具备独立确认通道」这个组合在注册表层就无法构造出来，
        // 因此传输层不必、也不应该再判一次同样的语义：判定只该有一处。
        assertThatThrownBy(() -> new A2aPeerTrustProfile(
                "batch-job", A2aPeerTrustRegistry.sha256(TRUSTED_TOKEN),
                TrustTier.TRUSTED_CONFIRMATION, A2aIdentityMode.SERVICE_ACCOUNT,
                42L, Set.of("order.query"), 3, true, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SERVICE_ACCOUNT");
    }

    @Test
    void aSelfContradictoryDecisionStillCannotOpenTheWritePath() {
        AgentHostConnector connector = calling(new ModelResult(
                ModelResult.Status.COMPLETED, Map.of(), null, null, null, null));
        // 注册表拦不住的唯一入口是「判定被直接构造出来」——例如未来新增一个身份来源。
        // 写前置策略要求信任分级与身份来源模式同时成立，任一不成立即恒只读，
        // 这里把不可能由配置产生的矛盾判定直接喂进来，钉住那个「同时」。
        A2aPolicyEnforcementFilter contradictory = mock(A2aPolicyEnforcementFilter.class);
        when(contradictory.evaluate(any(), any(), any())).thenReturn(
                new A2aPolicyEnforcementFilter.Decision(
                        A2aPolicyEnforcementFilter.Outcome.ADMITTED,
                        new AgentIdentity("batch-job",
                                A2aPeerTrustRegistry.sha256(TRUSTED_TOKEN),
                                TrustTier.TRUSTED_CONFIRMATION),
                        A2aIdentityMode.SERVICE_ACCOUNT, null));

        adapterWith(contradictory, handing(stateMapper.rejected(TASK)), connector)
                .handleTask(bearer(TRUSTED_TOKEN), TASK, invocation("cap_a1b2c3", Map.of()));

        verify(connector).call(any(), anyString(), anyString(), anyString(), any(), anyString(),
                anyString(), eq(AgentHostConnector.CallPolicy.READ_ONLY), any());
    }

    @Test
    void aConfirmationFromATrustedPeerCompletesTheTwoPhaseWrite() {
        AgentHostConnector connector = mock(AgentHostConnector.class);
        when(connector.confirm(any())).thenReturn(
                new AgentHostConnector.ConfirmationResult(true, "CONFIRMED", null));

        Task task = adapter(handing(stateMapper.rejected(TASK)), connector).handleTask(
                bearer(TRUSTED_TOKEN), TASK, data(Map.of(
                        A2aTaskRequest.FIELD_OPERATION_ID, "op-7")));

        assertThat(task.getStatus().state()).isEqualTo(TaskState.COMPLETED);
        verify(connector).confirm(new AgentHostConnector.UserConfirmationEvent(
                bearer(TRUSTED_TOKEN), "op-7"));
    }

    @Test
    void aServiceAccountCannotConfirmBecauseNoOneBehindItCanBearTheAction() {
        AgentHostConnector connector = mock(AgentHostConnector.class);

        Task task = adapter(registry(serviceAccountProfile()), handing(stateMapper.rejected(TASK)),
                connector, projectionService()).handleTask(bearer(TRUSTED_TOKEN), TASK,
                data(Map.of(A2aTaskRequest.FIELD_OPERATION_ID, "op-7")));

        // 允许它确认等于把二阶段写降级成一阶段。
        assertThat(task.getStatus().state()).isEqualTo(TaskState.AUTH_REQUIRED);
        verify(connector, never()).confirm(any());
        assertThat(audit.reasonCodes()).contains(
                A2aServerTransportAdapter.REASON_CONFIRMATION_NOT_ELIGIBLE);
    }

    @Test
    void aFailedConfirmationTravelsAsItsStableStateWithoutProviderWording() {
        AgentHostConnector connector = mock(AgentHostConnector.class);
        when(connector.confirm(any())).thenReturn(new AgentHostConnector.ConfirmationResult(
                false, "EXPIRED", "operation op-7 expired at provider order-service:20880"));

        Task task = adapter(handing(stateMapper.rejected(TASK)), connector).handleTask(
                bearer(TRUSTED_TOKEN), TASK, data(Map.of(
                        A2aTaskRequest.FIELD_OPERATION_ID, "op-7")));

        assertThat(task.getStatus().state()).isEqualTo(TaskState.FAILED);
        assertThat(payload(task)).containsEntry("errorCode", "EXPIRED");
        assertThat(payload(task).toString()).doesNotContain("order-service:20880");
    }

    @Test
    void aTerminalAuditIsWrittenBeforeTheArtifactIsHandedBack() {
        AgentHostConnector connector = calling(new ModelResult(
                ModelResult.Status.COMPLETED, Map.of("orderNo", "SO-1"),
                null, null, null, null));

        Task task = adapter(handing(stateMapper.rejected(TASK)), connector).handleTask(
                bearer(TRUSTED_TOKEN), TASK, invocation("cap_a1b2c3", Map.of()));

        assertThat(task.getStatus().state()).isEqualTo(TaskState.COMPLETED);
        assertThat(audit.eventTypes()).containsExactly(
                A2aTaskAuditRecorder.EventType.RECEIVED,
                A2aTaskAuditRecorder.EventType.COMPLETED);
        assertThat(audit.entries().get(1).details()).containsEntry("resultCode", "completed");
    }

    @Test
    void anUnpersistableTerminalAuditCostsTheArtifactRatherThanTheAuditTrail() {
        AgentHostConnector connector = calling(new ModelResult(
                ModelResult.Status.COMPLETED, Map.of("orderNo", "SO-1"),
                null, null, null, null));
        A2aServerTransportAdapter adapter = adapter(registry(trustedProfile()),
                handing(stateMapper.rejected(TASK)), connector, projectionService(),
                failingTerminalRecorder());

        Task task = adapter.handleTask(bearer(TRUSTED_TOKEN), TASK,
                invocation("cap_a1b2c3", Map.of()));

        // 一次成功的写操作若完全不留痕迹，审计就失去了存在的唯一理由——
        // 因此这里返回失败态，业务数据不出境。
        assertThat(task.getStatus().state()).isEqualTo(TaskState.FAILED);
        assertThat(payload(task)).containsEntry("errorCode",
                A2aServerTransportAdapter.AUDIT_UNAVAILABLE_CODE);
        assertThat(task.getArtifacts()).isEmpty();
    }

    @Test
    void aLostReceivedEventDoesNotTurnAuditJitterIntoInboundUnavailability() {
        AgentHostConnector connector = calling(new ModelResult(
                ModelResult.Status.COMPLETED, Map.of("orderNo", "SO-1"),
                null, null, null, null));
        A2aServerTransportAdapter adapter = adapter(registry(trustedProfile()),
                handing(stateMapper.rejected(TASK)), connector, projectionService(),
                failingReceivedRecorder());

        Task task = adapter.handleTask(bearer(TRUSTED_TOKEN), TASK,
                invocation("cap_a1b2c3", Map.of()));

        // 受理事件的丢失不会让一次已执行的操作失去痕迹：终态事件仍然记录了同一条任务。
        assertThat(task.getStatus().state()).isEqualTo(TaskState.COMPLETED);
    }

    @Test
    void anInputRequiredTerminalIsAuditedUnderItsOwnEventTypeNotAsACompletion() {
        AgentHostConnector connector = calling(new ModelResult(
                ModelResult.Status.CONFIRMATION_REQUIRED, Map.of(), null, null,
                "op-7", NOW.plusSeconds(300)));

        adapter(handing(stateMapper.rejected(TASK)), connector).handleTask(
                bearer(TRUSTED_TOKEN), TASK, invocation("cap_a1b2c3", Map.of()));

        // 「等待确认」与「已完成」对下游治理的含义完全不同，合并成一个事件会让统计失真。
        assertThat(audit.eventTypes()).containsExactly(
                A2aTaskAuditRecorder.EventType.RECEIVED,
                A2aTaskAuditRecorder.EventType.INPUT_REQUIRED);
    }

    @Test
    void thePublicCardIsAnonymousAndCarriesNoSkills() {
        A2aServerTransportAdapter.CardResult result =
                adapter(handing(stateMapper.rejected(TASK)), mock(AgentHostConnector.class))
                        .publicCard();

        assertThat(result.admitted()).isTrue();
        assertThat(result.card().skills()).isEmpty();
    }

    @Test
    void thePublicCardStillHonoursItsOwnQuota() {
        A2aServerTransportAdapter adapter = adapter(registry(trustedProfile()),
                handing(stateMapper.rejected(TASK)), mock(AgentHostConnector.class),
                projectionService(), audit, A2aRateLimiter.from(
                        new RateLimiterManager((dimension, key, permits) -> false)));

        A2aServerTransportAdapter.CardResult result = adapter.publicCard();

        // 匿名可达的端点必须有独立配额，否则匿名流量会直接挤掉正常业务。
        assertThat(result.admitted()).isFalse();
        assertThat(result.card()).isNull();
    }

    @Test
    void theExtendedCardNeedsAuthenticationAndReturnsNoCardWhenItIsMissing() {
        A2aServerTransportAdapter.CardResult result =
                adapter(handing(stateMapper.rejected(TASK)), mock(AgentHostConnector.class))
                        .extendedCard(RequestContext.empty());

        assertThat(result.outcome())
                .isEqualTo(A2aPolicyEnforcementFilter.Outcome.AUTH_REQUIRED);
        assertThat(result.card()).isNull();
    }

    @Test
    void anUnavailableProjectionContextFallsBackToThePublicCardRatherThanAnError() {
        A2aServerTransportAdapter.CardResult result =
                adapter(handing(stateMapper.rejected(TASK)), mock(AgentHostConnector.class))
                        .extendedCard(bearer(TRUSTED_TOKEN));

        // 失效关闭：宁可让对端看不见任何业务域，也不能在授权结论不确定时投影出可见面。
        // 同时这不是错误——认证已经通过，只是网关此刻无法确定该身份的可见面。
        assertThat(result.admitted()).isTrue();
        assertThat(result.card().skills()).isEmpty();
    }

    @Test
    void anAvailableProjectionIsEncodedVerbatimSoTheTransportAddsNoVisibilityOfItsOwn() {
        AgentCardProjection projected = new AgentCardProjection(
                "capability-gateway", "受治理的企业能力执行平面",
                "https://gateway.internal/a2a", "0.1.0", true,
                List.of("text/plain"), List.of("text/plain"),
                List.of(new AgentCardProjection.SkillProjection("domain.order", "订单域",
                        "订单相关的只读查询", List.of("read-only"), List.of())));

        A2aServerTransportAdapter.CardResult result = adapterWithCardProvider(
                (identity, context) -> java.util.Optional.of(projected))
                .extendedCard(bearer(TRUSTED_TOKEN));

        // 可见面完全由应用层投影决定；传输层只负责编码，既不追加也不裁剪任何一个域。
        // 这条同时钉住投影是「已完成的产物」——适配层没有任何机会在租约之外再读一次目录。
        assertThat(result.admitted()).isTrue();
        assertThat(result.card().skills()).hasSize(1);
        assertThat(result.card().skills().get(0).id()).isEqualTo("domain.order");
    }

    @Test
    void theProviderReceivesTheIdentityTheFilterDerivedRatherThanAnythingFromTheRequestBody() {
        List<AgentIdentity> seen = new ArrayList<>();

        adapterWithCardProvider((identity, context) -> {
            seen.add(identity);
            return java.util.Optional.empty();
        }).extendedCard(bearer(TRUSTED_TOKEN));

        // 身份只能来自策略执行点的判定：让 provider 自己去解析请求体，
        // 就等于给了「按报文声明的身份投影可见面」这条路径一个入口。
        assertThat(seen).hasSize(1);
        assertThat(seen.get(0).peerAgentName()).isEqualTo("orchestrator");
        assertThat(seen.get(0).peerDigest())
                .isEqualTo(A2aPeerTrustRegistry.sha256(TRUSTED_TOKEN));
        assertThat(seen.get(0).trustTier()).isEqualTo(TrustTier.TRUSTED_CONFIRMATION);
    }

    @Test
    void everyCollaboratorIsRequiredSoNoModeCanBeWiredIntoAFailOpenBranch() {
        assertThatThrownBy(() -> new A2aServerTransportAdapter(
                filter(registry(trustedProfile()), A2aRateLimiter.allowAll()), stateMapper,
                mock(AgentHostConnector.class), null, projectionService(),
                new AgentCardCodec(), A2aExtendedCardProvider.unavailable(), audit, "zh-CN"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("retrievalHandler");
    }

    private A2aServerTransportAdapter adapter(A2aRetrievalHandler retrieval,
                                              AgentHostConnector connector) {
        return adapter(registry(trustedProfile()), retrieval, connector, projectionService());
    }

    private A2aServerTransportAdapter adapter(A2aPeerTrustRegistry registry,
                                              A2aRetrievalHandler retrieval,
                                              AgentHostConnector connector,
                                              AgentCardProjectionService projections) {
        return adapter(registry, retrieval, connector, projections, audit);
    }

    private A2aServerTransportAdapter adapter(A2aPeerTrustRegistry registry,
                                              A2aRetrievalHandler retrieval,
                                              AgentHostConnector connector,
                                              AgentCardProjectionService projections,
                                              A2aTaskAuditRecorder recorder) {
        return adapter(registry, retrieval, connector, projections, recorder,
                A2aRateLimiter.allowAll());
    }

    private A2aServerTransportAdapter adapter(A2aPeerTrustRegistry registry,
                                              A2aRetrievalHandler retrieval,
                                              AgentHostConnector connector,
                                              AgentCardProjectionService projections,
                                              A2aTaskAuditRecorder recorder,
                                              A2aRateLimiter rateLimiter) {
        return new A2aServerTransportAdapter(filter(registry, rateLimiter), stateMapper,
                connector, retrieval, projections, new AgentCardCodec(),
                A2aExtendedCardProvider.unavailable(), recorder, "zh-CN");
    }

    /** 只在需要喂入一个配置层无法产生的判定时使用；其余场景一律用真实策略执行点。 */
    private A2aServerTransportAdapter adapterWith(A2aPolicyEnforcementFilter policyFilter,                                                  A2aRetrievalHandler retrieval,
                                                  AgentHostConnector connector) {
        return new A2aServerTransportAdapter(policyFilter, stateMapper, connector, retrieval,
                projectionService(), new AgentCardCodec(),
                A2aExtendedCardProvider.unavailable(), audit, "zh-CN");
    }

    /** 扩展卡路径专用：只替换投影入口，策略执行点与编码器仍是真实实现。 */
    private A2aServerTransportAdapter adapterWithCardProvider(A2aExtendedCardProvider provider) {
        return new A2aServerTransportAdapter(
                filter(registry(trustedProfile()), A2aRateLimiter.allowAll()), stateMapper,
                mock(AgentHostConnector.class), handing(stateMapper.rejected(TASK)),
                projectionService(), new AgentCardCodec(), provider, audit, "zh-CN");
    }

    /**
     * 用真实的策略执行点而不是替身：传输适配器的价值恰恰在于「按固定顺序调用既有判定」，
     * 把判定替换掉就等于把被测的那条性质本身抽走了。
     */
    private static A2aPolicyEnforcementFilter filter(A2aPeerTrustRegistry registry,
                                                     A2aRateLimiter rateLimiter) {
        return new A2aPolicyEnforcementFilter(registry, rateLimiter, null,
                A2aSelectionMode.DELEGATED_SELECTION, 3, new NoopTelemetry());
    }

    private static A2aPeerTrustRegistry registry(A2aPeerTrustProfile... profiles) {
        return new A2aPeerTrustRegistry(List.of(profiles));
    }

    private static A2aPeerTrustProfile trustedProfile() {
        return new A2aPeerTrustProfile("orchestrator",
                A2aPeerTrustRegistry.sha256(TRUSTED_TOKEN), TrustTier.TRUSTED_CONFIRMATION,
                A2aIdentityMode.ON_BEHALF_OF, null, null, 3, true, null);
    }

    /** 服务账号只能配到只读档；能力范围必须显式白名单化，否则注册表本身就会拒绝。 */
    private static A2aPeerTrustProfile serviceAccountProfile() {
        return new A2aPeerTrustProfile("batch-job",
                A2aPeerTrustRegistry.sha256(TRUSTED_TOKEN), TrustTier.READ_ONLY,
                A2aIdentityMode.SERVICE_ACCOUNT, 42L, Set.of("order.query"), 3, true, null);
    }

    private static AgentCardProjectionService projectionService() {
        AgentCardProjectionService service = mock(AgentCardProjectionService.class);
        when(service.publicCard()).thenReturn(PUBLIC_CARD);
        return service;
    }

    private static A2aRetrievalHandler handing(Task task) {
        A2aRetrievalHandler handler = mock(A2aRetrievalHandler.class);
        when(handler.handle(any())).thenReturn(task);
        return handler;
    }

    private static AgentHostConnector calling(ModelResult result) {
        AgentHostConnector connector = mock(AgentHostConnector.class);
        when(connector.call(any(), anyString(), anyString(), anyString(), any(), anyString(),
                anyString(), any(), any()))
                .thenReturn(new AgentHostConnector.CallResult(result, null, null));
        return connector;
    }

    private static A2aRetrievalHandler.Request capture(A2aRetrievalHandler handler) {
        ArgumentCaptor<A2aRetrievalHandler.Request> captor =
                ArgumentCaptor.forClass(A2aRetrievalHandler.Request.class);
        verify(handler).handle(captor.capture());
        return captor.getValue();
    }

    private static String idempotencyKey(AgentHostConnector connector) {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(connector).call(any(), anyString(), anyString(), anyString(), any(), anyString(),
                captor.capture(), any(), any());
        return captor.getValue();
    }

    private static RequestContext bearer(String token) {
        return new RequestContext(Map.of("Authorization", "Bearer " + token),
                Map.of(), Map.of(), "10.0.0.1");
    }

    private static Message text(String value) {
        return message(new TextPart(value));
    }

    private static Message data(Map<String, Object> value) {
        return message(new DataPart(value));
    }

    private static Message invocation(String toolRef, Map<String, Object> arguments) {
        return message(new DataPart(Map.of(A2aTaskRequest.FIELD_TOOL_REF, toolRef,
                A2aTaskRequest.FIELD_ARGUMENTS, arguments)));
    }

    private static Message message(Part<?>... parts) {
        return new Message.Builder().role(Message.Role.USER).parts(parts).build();
    }

    private static Map<String, Object> payload(Task task) {
        return task.getStatus().message().getParts().stream()
                .filter(DataPart.class::isInstance)
                .map(part -> ((DataPart) part).getData())
                .findFirst()
                .orElseThrow(() -> new AssertionError("no DataPart present"));
    }

    private static A2aTaskAuditRecorder failingTerminalRecorder() {
        return entry -> {
            if (entry.eventType() != A2aTaskAuditRecorder.EventType.RECEIVED) {
                throw new IllegalStateException("audit store unavailable");
            }
        };
    }

    private static A2aTaskAuditRecorder failingReceivedRecorder() {
        return entry -> {
            if (entry.eventType() == A2aTaskAuditRecorder.EventType.RECEIVED) {
                throw new IllegalStateException("audit store unavailable");
            }
        };
    }

    /** 记录审计条目：断言的是「写了什么、按什么顺序」，用 mock 表达反而更晦涩。 */
    private static final class RecordingAuditRecorder implements A2aTaskAuditRecorder {

        private final List<Entry> entries = new ArrayList<>();

        @Override
        public void record(Entry entry) {
            entries.add(entry);
        }

        private List<Entry> entries() {
            return entries;
        }

        private List<EventType> eventTypes() {
            return entries.stream().map(Entry::eventType).toList();
        }

        private List<String> reasonCodes() {
            return entries.stream().map(Entry::reasonCode).filter(java.util.Objects::nonNull)
                    .toList();
        }
    }

    /** 埋点在本类的被测性质里不承载任何断言，因此只需要一个不做事的实现。 */
    private static final class NoopTelemetry implements TelemetryPort {

        @Override
        public <T> T observe(String name, Map<String, String> tags,
                            java.util.function.Supplier<T> action) {
            return action.get();
        }

        @Override
        public void increment(String metric, Map<String, String> tags) {
        }

        @Override
        public void recordDuration(String metric, long durationNanos,
                                   Map<String, String> tags) {
        }

        @Override
        public void recordValue(String metric, long value, Map<String, String> tags) {
        }
    }
}
