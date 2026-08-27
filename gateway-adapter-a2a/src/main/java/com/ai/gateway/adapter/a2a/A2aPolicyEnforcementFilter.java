package com.ai.gateway.adapter.a2a;

import com.ai.gateway.domain.model.A2aTaskContext;
import com.ai.gateway.domain.model.AgentIdentity;
import com.ai.gateway.domain.model.RequestContext;
import com.ai.gateway.domain.model.TrustTier;
import com.ai.gateway.domain.port.TelemetryPort;
import com.ai.gateway.domain.service.InstructionInjectionDetector;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * A2A 入站策略执行点（设计 §3.4 第 1、2 步）。
 *
 * <p>本过滤器<b>只做准入判断，不做授权判断</b>。能力可见性、参数校验、权限判定全部由
 * {@code AgentHostConnector} 背后的确定性执行链完成——适配器重复实现其中任何一条，
 * 都会产生「两处判定不一致」的缺口，而这种缺口在单侧测试里看不出来。
 * 这里负责的是四件协议层面的事，它们在执行链里没有对应位置：</p>
 * <ol>
 * <li><b>peer 是否已认证</b>：无凭据连接不进入检索，直接 {@code AUTH_REQUIRED}。</li>
 * <li><b>委托跳数</b>：A2A 协议本身没有跳数概念，不补这条防护，Agent 环路会把一次请求
 * 放大成不可控扇出，而每一跳看起来都完全合法。</li>
 * <li><b>限流</b>：按暴露面而非按 peer，理由见 {@link A2aRateLimiter}。</li>
 * <li><b>入站文本注入检测</b>：与出站清单叙述共用 {@link InstructionInjectionDetector}
 * 的同一份模式列表。</li>
 * </ol>
 *
 * <p>判定结果的<b>对外</b>表达只有三种（{@code AUTH_REQUIRED} / {@code REJECTED} /
 * 放行），而 {@link Decision#reasonCode()} 只写审计：注入命中、跳数超限与请求形态不受理
 * 在对端看来必须完全一致，否则对端可以据此逐步探测网关的内部判定规则。</p>
 *
 * <p>本类不可变且线程安全。</p>
 *
 * @author cmiracle@163.com
 * @since 0.1.0
 */
public final class A2aPolicyEnforcementFilter {

    /** 审计侧原因码：入站文本命中注入模式。 */
    public static final String REASON_INJECTION = "INJECTION_DETECTED";

    /** 审计侧原因码：委托跳数超过上限。 */
    public static final String REASON_DEPTH = "DELEGATION_DEPTH_EXCEEDED";

    /** 审计侧原因码：连接未携带可认证凭据。 */
    public static final String REASON_UNAUTHENTICATED = "PEER_NOT_AUTHENTICATED";

    /** 审计侧原因码：当前选择模式不受理该请求形态。 */
    public static final String REASON_SHAPE = "REQUEST_SHAPE_NOT_ACCEPTED";

    /** 审计侧原因码：触发入站限流。 */
    public static final String REASON_RATE_LIMITED = "RATE_LIMITED";

    private static final String METRIC_ADMISSION = "gateway.a2a.task.admission";
    private static final String METRIC_DELEGATION_REJECTED = "gateway.a2a.delegation.rejected";

    private final A2aPeerTrustRegistry trustRegistry;
    private final A2aRateLimiter rateLimiter;
    private final InstructionInjectionDetector injectionDetector;
    private final A2aSelectionMode selectionMode;
    private final int maxDelegationDepth;
    private final TelemetryPort telemetry;

    /**
     * @param trustRegistry      对端信任注册表，不能为 {@code null}
     * @param rateLimiter        入站限流器，不能为 {@code null}
     * @param injectionDetector  注入检测器，{@code null} 时使用内置模式集
     * @param selectionMode      当前部署的选择模式，不能为 {@code null}
     * @param maxDelegationDepth 部署级委托跳数上限，必须为正
     * @param telemetry          埋点端口，不能为 {@code null}
     */
    public A2aPolicyEnforcementFilter(A2aPeerTrustRegistry trustRegistry,
                                      A2aRateLimiter rateLimiter,
                                      InstructionInjectionDetector injectionDetector,
                                      A2aSelectionMode selectionMode,
                                      int maxDelegationDepth,
                                      TelemetryPort telemetry) {
        this.trustRegistry = Objects.requireNonNull(trustRegistry, "trustRegistry");
        this.rateLimiter = Objects.requireNonNull(rateLimiter, "rateLimiter");
        this.injectionDetector = injectionDetector == null
                ? InstructionInjectionDetector.builtIn() : injectionDetector;
        this.selectionMode = Objects.requireNonNull(selectionMode, "selectionMode");
        if (maxDelegationDepth <= 0) {
            throw new IllegalArgumentException("maxDelegationDepth must be positive");
        }
        this.maxDelegationDepth = maxDelegationDepth;
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
    }

    /**
     * 判定一次入站 Task 是否可以进入确定性执行链。
     *
     * <p>判定顺序不是随意的：先认证（无凭据的请求不该消耗任何后续资源），再查跳数
     * （防护环路放大），再限流（保护检索与执行），最后才做正则扫描（本链路里最贵的一步）。
     * 把扫描提前会让一次注入攻击顺带获得可观的 CPU 放大。</p>
     *
     * @param context     入站请求上下文，允许为 {@code null}
     * @param taskContext 任务上下文，不能为 {@code null}
     * @param request     请求形态与文本，不能为 {@code null}
     * @return 准入判定
     */
    public Decision evaluate(RequestContext context, A2aTaskContext taskContext,
                             InboundRequest request) {
        Objects.requireNonNull(taskContext, "taskContext must not be null");
        Objects.requireNonNull(request, "request must not be null");

        AgentIdentity identity = trustRegistry.identify(context);
        if (identity.trustTier() == TrustTier.UNTRUSTED) {
            return reject(Outcome.AUTH_REQUIRED, identity, REASON_UNAUTHENTICATED);
        }
        if (!taskContext.withinDepth(effectiveDepthLimit(context))) {
            // 单独计数：跳数拒绝往往意味着上游存在环路，与普通拒绝的处置方式不同。
            telemetry.increment(METRIC_DELEGATION_REJECTED,
                    Map.of("depth", String.valueOf(taskContext.delegationDepth())));
            return reject(Outcome.REJECTED, identity, REASON_DEPTH);
        }
        if (!acceptsShape(request)) {
            return reject(Outcome.REJECTED, identity, REASON_SHAPE);
        }
        if (!rateLimiter.tryAcquire(A2aRateLimiter.TASK)) {
            return reject(Outcome.REJECTED, identity, REASON_RATE_LIMITED);
        }
        if (injectionDetector.detectsAny(request.texts())) {
            return reject(Outcome.REJECTED, identity, REASON_INJECTION);
        }
        telemetry.increment(METRIC_ADMISSION, Map.of("outcome", "admitted"));
        return new Decision(Outcome.ADMITTED, identity,
                trustRegistry.identityMode(context), null);
    }

    /**
     * 判定一次认证后扩展卡请求是否放行。
     *
     * <p>扩展卡本身不返回任何未授权的能力（投影阶段已按 Principal 过滤），因此这里只需要
     * 认证与限流两道；卡片内容的可见面由 {@code AgentCardProjectionService} 决定。</p>
     *
     * @param context 入站请求上下文，允许为 {@code null}
     * @return 准入判定；放行时 {@link Outcome#ADMITTED}
     */
    public Decision evaluateExtendedCard(RequestContext context) {
        AgentIdentity identity = trustRegistry.identify(context);
        if (identity.trustTier() == TrustTier.UNTRUSTED) {
            return reject(Outcome.AUTH_REQUIRED, identity, REASON_UNAUTHENTICATED);
        }
        if (!rateLimiter.tryAcquire(A2aRateLimiter.EXTENDED_CARD)) {
            return reject(Outcome.REJECTED, identity, REASON_RATE_LIMITED);
        }
        return new Decision(Outcome.ADMITTED, identity,
                trustRegistry.identityMode(context), null);
    }

    /**
     * 判定一次公开卡请求是否放行。
     *
     * <p>公开卡是匿名可达的，因此这里只有限流一道防线，也正因如此它必须有<b>独立</b>的
     * 资源键：与 Task 共用配额会让匿名流量直接挤掉正常业务。</p>
     *
     * @return 放行时返回 {@code true}
     */
    public boolean allowPublicCard() {
        return rateLimiter.tryAcquire(A2aRateLimiter.PUBLIC_CARD);
    }

    /**
     * 返回本次请求实际生效的跳数上限：部署级上限与档案配置取更严的一侧。
     *
     * <p>取 min 而不是让档案覆盖部署配置，是因为档案是按 peer 维护的，
     * 允许它放宽部署上限就等于把整体防护的下限交给每一份单独的注册记录。</p>
     */
    private int effectiveDepthLimit(RequestContext context) {
        return Math.min(maxDelegationDepth, trustRegistry.maxDelegationDepth(context));
    }

    /** 请求形态是否被当前选择模式受理。 */
    private boolean acceptsShape(InboundRequest request) {
        return switch (request.shape()) {
            case FREE_TEXT -> selectionMode.acceptsFreeText();
            case STRUCTURED_SELECTION -> selectionMode.acceptsStructuredSelection();
        };
    }

    private Decision reject(Outcome outcome, AgentIdentity identity, String reasonCode) {
        telemetry.increment(METRIC_ADMISSION,
                Map.of("outcome", outcome.name().toLowerCase(Locale.ROOT)));
        return new Decision(outcome, identity, A2aIdentityMode.SERVICE_ACCOUNT, reasonCode);
    }

    /** 准入判定的对外结果，只有三种；内部原因不在此列。 */
    public enum Outcome {

        /** 放行，进入确定性执行链。 */
        ADMITTED,

        /** 需要更强的身份：连接未携带可认证凭据。 */
        AUTH_REQUIRED,

        /** 拒绝受理；对端不可据此区分具体原因。 */
        REJECTED
    }

    /**
     * 准入判定结果。
     *
     * <p>{@code identityMode} 在被拒绝时固定为 {@link A2aIdentityMode#SERVICE_ACCOUNT}——
     * 两者中恒只读的一侧。这样即使调用方误用了一个被拒判定，也不可能因此走到写路径。</p>
     *
     * @param outcome      对外结果
     * @param identity     网关判定的对端身份，恒不为 {@code null}
     * @param identityMode 身份来源模式；被拒时为最窄的一档
     * @param reasonCode   审计侧原因码，放行时为 {@code null}；<b>不得写入任何对端可见的响应</b>
     */
    public record Decision(Outcome outcome, AgentIdentity identity,
                           A2aIdentityMode identityMode, String reasonCode) {

        /**
         * @return 是否放行
         */
        public boolean admitted() {
            return outcome == Outcome.ADMITTED;
        }
    }

    /**
     * 一次入站请求的形态与待检测文本。
     *
     * @param shape 请求形态
     * @param texts 需要做注入检测的文本片段，{@code null} 视为空集合
     */
    public record InboundRequest(Shape shape, Collection<String> texts) {

        /**
         * 紧凑构造器：冻结文本集合。
         *
         * @param shape 请求形态，不能为 {@code null}
         * @param texts 待检测文本
         */
        public InboundRequest {
            Objects.requireNonNull(shape, "shape must not be null");
            texts = texts == null ? List.of() : List.copyOf(texts);
        }

        /**
         * @param texts 自由文本片段
         * @return 自由文本形态的请求
         */
        public static InboundRequest freeText(String... texts) {
            return new InboundRequest(Shape.FREE_TEXT, List.of(texts));
        }

        /**
         * 结构化选择形态的请求。
         *
         * <p>结构化请求同样要做文本检测：{@code DataPart} 里的字符串参数一样会被下游拼进
         * 提示或日志，「结构化」只是形态，不代表内容可信。</p>
         *
         * @param texts 结构化载荷中的字符串片段
         * @return 结构化形态的请求
         */
        public static InboundRequest structured(Collection<String> texts) {
            return new InboundRequest(Shape.STRUCTURED_SELECTION, texts);
        }

        /** 请求形态。 */
        public enum Shape {

            /** 自由文本首跳（{@code TextPart}）。 */
            FREE_TEXT,

            /** 结构化选择（{@code DataPart}），可能是首跳也可能是第二跳。 */
            STRUCTURED_SELECTION
        }
    }
}
