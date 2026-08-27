package com.ai.gateway.adapter.a2a;

import com.ai.gateway.application.agent.AgentCardProjection;
import com.ai.gateway.application.agent.AgentCardProjectionService;
import com.ai.gateway.application.agent.AgentHostConnector;
import com.ai.gateway.application.agent.AgentModelResultMapper.ModelResult;
import com.ai.gateway.domain.model.A2aTaskContext;
import com.ai.gateway.domain.model.AgentIdentity;
import com.ai.gateway.domain.model.AuditPlane;
import com.ai.gateway.domain.model.RequestContext;
import com.ai.gateway.domain.model.TrustTier;
import com.ai.gateway.domain.service.Sha256Digest;
import io.a2a.spec.AgentCard;
import io.a2a.spec.Message;
import io.a2a.spec.Task;
import io.a2a.spec.TaskState;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A2A 入站传输适配器：把 A2A 的三个端点汇入网关既有的确定性执行链（设计 §3.4、§3.9）。
 *
 * <p>本类是<b>编排</b>而非<b>判定</b>：它按固定顺序调用策略执行点、检索策略、执行链与状态映射器，
 * 自己不新增任何一条安全判断。这一点是有意为之——{@code AgentHostConnector} 的约定是
 * "Transport adapters must not duplicate these checks"，而重复实现任何一条校验都会产生
 * 「两处判定不一致」的缺口，且这种缺口在单侧测试里看不出来。</p>
 *
 * <p>承载的三个端点：</p>
 * <ol>
 * <li><b>公开卡</b>——匿名可达，{@code skills} 恒为空，只受独立配额约束。</li>
 * <li><b>扩展卡</b>——需认证；投影上下文不可用时回退为公开卡（失效关闭）。</li>
 * <li><b>Task</b>——{@code message/send} 的处理链：意图分类 → 策略执行点 → 检索或执行 →
 * 状态映射，终态审计先落库再返回。</li>
 * </ol>
 *
 * <p>入站消息的意图由 {@link A2aTaskRequest} 确定性分类，三条路径彼此独立：检索（回传候选集）、
 * 执行（凭首跳签发的 {@code toolRef}）、确认（凭 {@code operationId}）。
 * 其中<b>确认路径只对具备独立确认通道的受信 peer 开放</b>：服务账号背后没有最终用户，
 * 没人能承担确认动作，允许它确认等于把二阶段写降级成一阶段。</p>
 *
 * <p><b>审计与返回的顺序不可交换。</b>终态事件先落库再返回产物，落库失败时返回失败态而不是业务数据。
 * 若顺序反了，一次成功的写操作可能完全不留痕迹，而那正是审计存在的唯一理由。</p>
 *
 * <p>本类不可变且线程安全。</p>
 *
 * @author cmiracle@163.com
 * @since 0.1.0
 */
public final class A2aServerTransportAdapter {

    /** 审计落库失败时对外的稳定错误码。 */
    public static final String AUDIT_UNAVAILABLE_CODE = "AUDIT_UNAVAILABLE";

    /** 载荷不足以确定意图时的审计侧原因码。 */
    public static final String REASON_MALFORMED = "REQUEST_INTENT_UNDETERMINED";

    /** 非受信 peer 试图完成写确认时的审计侧原因码。 */
    public static final String REASON_CONFIRMATION_NOT_ELIGIBLE = "PEER_NOT_CONFIRMATION_ELIGIBLE";

    private final A2aPolicyEnforcementFilter policyFilter;
    private final A2aTaskStateMapper stateMapper;
    private final AgentHostConnector connector;
    private final A2aRetrievalHandler retrievalHandler;
    private final AgentCardProjectionService cardProjectionService;
    private final AgentCardCodec cardCodec;
    private final A2aExtendedCardProvider extendedCardProvider;
    private final A2aTaskAuditRecorder auditRecorder;
    private final String locale;

    /**
     * @param policyFilter          入站策略执行点，不能为 {@code null}
     * @param stateMapper           状态映射器，不能为 {@code null}
     * @param connector             协议中立的 Agent 回合连接器，不能为 {@code null}
     * @param retrievalHandler      首跳检索策略，不能为 {@code null}
     * @param cardProjectionService 分级卡片投影服务，不能为 {@code null}
     * @param cardCodec             卡片编码器，不能为 {@code null}
     * @param extendedCardProvider  扩展卡投影入口，不能为 {@code null}
     * @param auditRecorder         入站平面审计出口，不能为 {@code null}
     * @param locale                执行时使用的语言标签，{@code null} 时使用 {@code zh-CN}
     */
    public A2aServerTransportAdapter(A2aPolicyEnforcementFilter policyFilter,
                                     A2aTaskStateMapper stateMapper,
                                     AgentHostConnector connector,
                                     A2aRetrievalHandler retrievalHandler,
                                     AgentCardProjectionService cardProjectionService,
                                     AgentCardCodec cardCodec,
                                     A2aExtendedCardProvider extendedCardProvider,
                                     A2aTaskAuditRecorder auditRecorder,
                                     String locale) {
        this.policyFilter = Objects.requireNonNull(policyFilter, "policyFilter must not be null");
        this.stateMapper = Objects.requireNonNull(stateMapper, "stateMapper must not be null");
        this.connector = Objects.requireNonNull(connector, "connector must not be null");
        this.retrievalHandler = Objects.requireNonNull(
                retrievalHandler, "retrievalHandler must not be null");
        this.cardProjectionService = Objects.requireNonNull(
                cardProjectionService, "cardProjectionService must not be null");
        this.cardCodec = Objects.requireNonNull(cardCodec, "cardCodec must not be null");
        this.extendedCardProvider = Objects.requireNonNull(
                extendedCardProvider, "extendedCardProvider must not be null");
        this.auditRecorder = Objects.requireNonNull(
                auditRecorder, "auditRecorder must not be null");
        this.locale = locale == null || locale.isBlank() ? "zh-CN" : locale;
    }

    /**
     * 返回身份无关的公开卡。
     *
     * @return 卡片结果；命中配额上限时 {@link A2aPolicyEnforcementFilter.Outcome#REJECTED}
     */
    public CardResult publicCard() {
        if (!policyFilter.allowPublicCard()) {
            return new CardResult(A2aPolicyEnforcementFilter.Outcome.REJECTED, null);
        }
        return new CardResult(A2aPolicyEnforcementFilter.Outcome.ADMITTED,
                cardCodec.encode(cardProjectionService.publicCard()));
    }

    /**
     * 返回按调用方身份裁剪的扩展卡。
     *
     * <p>上下文不可用时返回的是<b>公开卡</b>而不是错误：认证已经通过，只是网关此刻无法确定
     * 该身份的可见面，此时给出一张不含任何业务域的卡片既不泄露信息，也不迫使对端把
     * 「暂时不可用」与「无权限」混为一谈。</p>
     *
     * @param context 入站请求上下文，允许为 {@code null}
     * @return 卡片结果
     */
    public CardResult extendedCard(RequestContext context) {
        A2aPolicyEnforcementFilter.Decision decision = policyFilter.evaluateExtendedCard(context);
        if (!decision.admitted()) {
            return new CardResult(decision.outcome(), null);
        }
        // 投影在 provider 内部连同租约一次完成，本方法拿到的已经是不再引用目录视图的产物。
        AgentCardProjection projection = extendedCardProvider
                .extendedCard(decision.identity(),
                        context == null ? RequestContext.empty() : context)
                .orElseGet(cardProjectionService::publicCard);
        return new CardResult(A2aPolicyEnforcementFilter.Outcome.ADMITTED,
                cardCodec.encode(projection));
    }

    /**
     * 处理一次入站 Task。
     *
     * @param context     入站请求上下文，允许为 {@code null}
     * @param taskContext 任务上下文，不能为 {@code null}
     * @param message     入站消息，允许为 {@code null}（视为形态不可判定）
     * @return 可直接回复对端的任务
     */
    public Task handleTask(RequestContext context, A2aTaskContext taskContext, Message message) {
        Objects.requireNonNull(taskContext, "taskContext must not be null");
        A2aTaskRequest request = A2aTaskRequest.from(message);
        A2aPolicyEnforcementFilter.Decision decision = policyFilter.evaluate(
                context, taskContext, request.toInboundRequest());
        if (!decision.admitted()) {
            return refuse(taskContext, decision, decision.reasonCode());
        }
        audit(A2aTaskAuditRecorder.EventType.RECEIVED, taskContext, decision.identity(),
                null, receivedDetails(taskContext, request));
        if (request.kind() == A2aTaskRequest.Kind.MALFORMED) {
            return refuse(taskContext, decision, REASON_MALFORMED);
        }
        Task task = dispatch(context, taskContext, decision, request);
        return recordTerminal(taskContext, decision.identity(), task);
    }

    /** 按意图分派到三条彼此独立的路径。 */
    private Task dispatch(RequestContext context, A2aTaskContext taskContext,
                          A2aPolicyEnforcementFilter.Decision decision,
                          A2aTaskRequest request) {
        return switch (request.kind()) {
            case RETRIEVAL -> retrievalHandler.handle(new A2aRetrievalHandler.Request(
                    safeContext(context), taskContext, decision, request.query(),
                    taskContext.rootRequestId()));
            case TOOL_INVOCATION -> invoke(context, taskContext, decision, request);
            case CONFIRMATION -> confirm(context, taskContext, decision, request);
            case MALFORMED -> stateMapper.rejected(taskContext);
        };
    }

    /**
     * 凭首跳签发的 {@code toolRef} 执行一次能力调用。
     *
     * <p>幂等键由 {@code (taskId, toolRef, 入参)} 派生而非随机生成：A2A 的重传与对端重试都会带回
     * 同一个 {@code taskId}，派生键让重复投递收敛到同一次操作，而随机键会把一次重试变成第二次写。</p>
     */
    private Task invoke(RequestContext context, A2aTaskContext taskContext,
                        A2aPolicyEnforcementFilter.Decision decision,
                        A2aTaskRequest request) {
        AgentHostConnector.CallResult result = connector.call(
                safeContext(context), taskContext.taskId(), taskContext.rootRequestId(),
                request.toolRef(), request.arguments(), locale,
                idempotencyKey(taskContext, request), callPolicy(decision),
                AuditPlane.A2A_INBOUND);
        return stateMapper.toTask(taskContext, result.result());
    }

    /**
     * 确认一次已准备好的写操作。
     *
     * <p>只有具备独立确认通道的受信 peer 可以走到这里。返回 {@code AUTH_REQUIRED} 而不是
     * {@code REJECTED}，是因为「去完成受信注册」对调用方是可行动的信息，
     * 且不透露任何能力面的存在与否。</p>
     */
    private Task confirm(RequestContext context, A2aTaskContext taskContext,
                         A2aPolicyEnforcementFilter.Decision decision,
                         A2aTaskRequest request) {
        if (!confirmationEligible(decision)) {
            audit(A2aTaskAuditRecorder.EventType.REJECTED, taskContext, decision.identity(),
                    REASON_CONFIRMATION_NOT_ELIGIBLE, Map.of());
            return stateMapper.authRequired(taskContext);
        }
        AgentHostConnector.ConfirmationResult result = connector.confirm(
                new AgentHostConnector.UserConfirmationEvent(
                        safeContext(context), request.operationId()));
        if (result.success()) {
            return stateMapper.toTask(taskContext, new ModelResult(
                    ModelResult.Status.COMPLETED, Map.of(), null, null, null, null));
        }
        // 只带稳定状态码：确认失败的具体措辞来自执行链与 Provider，一律不出境。
        return stateMapper.toTask(taskContext, new ModelResult(
                ModelResult.Status.ERROR, null, result.state(), null, null, null));
    }

    /**
     * 写前置确认策略。
     *
     * <p>两个条件必须同时成立：信任分级为「具备独立确认通道」，且身份来源模式本身可写。
     * 只看其中一个都不够——服务账号即使被配得再高也没有能承担确认的最终用户，
     * 而未注册 peer 即使走的是用户身份透传也还没被授予写路径。</p>
     */
    private static AgentHostConnector.CallPolicy callPolicy(
            A2aPolicyEnforcementFilter.Decision decision) {
        return confirmationEligible(decision)
                ? AgentHostConnector.CallPolicy.HOST_CONFIRMATION
                : AgentHostConnector.CallPolicy.READ_ONLY;
    }

    private static boolean confirmationEligible(A2aPolicyEnforcementFilter.Decision decision) {
        return decision.identity().trustTier() == TrustTier.TRUSTED_CONFIRMATION
                && decision.identityMode().writeEligible();
    }

    /** 拒绝路径：先写审计原因码，再返回对端不可区分的结果。 */
    private Task refuse(A2aTaskContext taskContext,
                        A2aPolicyEnforcementFilter.Decision decision,
                        String reasonCode) {
        audit(A2aTaskAuditRecorder.EventType.REJECTED, taskContext,
                decision.identity(), reasonCode, Map.of());
        return decision.outcome() == A2aPolicyEnforcementFilter.Outcome.AUTH_REQUIRED
                ? stateMapper.authRequired(taskContext)
                : stateMapper.rejected(taskContext);
    }

    /**
     * 终态审计先落库，再返回业务产物。
     *
     * <p>落库失败时返回失败态：把审计当作可失败的前置步骤，是「审计落库失败不返回 Artifact」
     * 这条约束唯一可被测试验证的形态。</p>
     */
    private Task recordTerminal(A2aTaskContext taskContext, AgentIdentity identity, Task task) {
        TaskState state = task.getStatus().state();
        A2aTaskAuditRecorder.EventType eventType = state == TaskState.INPUT_REQUIRED
                ? A2aTaskAuditRecorder.EventType.INPUT_REQUIRED
                : A2aTaskAuditRecorder.EventType.COMPLETED;
        try {
            auditRecorder.record(new A2aTaskAuditRecorder.Entry(eventType, taskContext,
                    identity, null, Map.of("resultCode", state.asString())));
        } catch (RuntimeException e) {
            return stateMapper.toTask(taskContext, new ModelResult(
                    ModelResult.Status.ERROR, null, AUDIT_UNAVAILABLE_CODE,
                    null, null, null));
        }
        return task;
    }

    /**
     * 记录一次非终态事件。
     *
     * <p>与终态不同，这里吞掉落库异常：受理事件的丢失不会让一次已执行的操作失去痕迹
     * （终态事件仍会记录同一条任务），而让它中断请求反而会把审计基础设施的抖动
     * 直接变成入站不可用。</p>
     */
    private void audit(A2aTaskAuditRecorder.EventType eventType, A2aTaskContext taskContext,
                       AgentIdentity identity, String reasonCode, Map<String, Object> details) {
        try {
            auditRecorder.record(new A2aTaskAuditRecorder.Entry(
                    eventType, taskContext, identity, reasonCode, details));
        } catch (RuntimeException ignored) {
            // 见方法 Javadoc：非终态事件不阻断请求。
        }
    }

    private static Map<String, Object> receivedDetails(A2aTaskContext taskContext,
                                                       A2aTaskRequest request) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("delegationDepth", taskContext.delegationDepth());
        details.put("intent", request.kind().name());
        return details;
    }

    /** 派生幂等键：只用摘要，不把入参原文带入任何键空间。 */
    private static String idempotencyKey(A2aTaskContext taskContext, A2aTaskRequest request) {
        return Sha256Digest.sha256Hex(taskContext.taskId() + "\n" + request.toolRef()
                + "\n" + request.arguments());
    }

    private static RequestContext safeContext(RequestContext context) {
        return context == null ? RequestContext.empty() : context;
    }

    /**
     * 卡片请求结果。
     *
     * @param outcome 对外结果；非 {@link A2aPolicyEnforcementFilter.Outcome#ADMITTED} 时无卡片
     * @param card    卡片，被拒时为 {@code null}
     */
    public record CardResult(A2aPolicyEnforcementFilter.Outcome outcome, AgentCard card) {

        /**
         * @return 是否放行
         */
        public boolean admitted() {
            return outcome == A2aPolicyEnforcementFilter.Outcome.ADMITTED;
        }
    }
}
