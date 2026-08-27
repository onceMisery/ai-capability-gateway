package com.ai.gateway.adapter.a2a;

import com.ai.gateway.application.agent.AgentCapabilityResolver;
import com.ai.gateway.application.agent.AgentHostConnector;
import com.ai.gateway.application.agent.AgentModelResultMapper.ModelResult;
import io.a2a.spec.Task;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 默认档 {@link A2aSelectionMode#DELEGATED_SELECTION} 的首跳处理：
 * 网关只做确定性检索，选择权留在对端（设计 §3.4.1）。
 *
 * <p>这一档<b>不调用任何模型</b>。它做的三件事全是确定性变换：按 Principal 过滤可见集合、
 * BM25 检索、投影成短别名句柄。候选集用 A2A 原生的 {@code TaskStatus(INPUT_REQUIRED, DataPart)}
 * 回传，对端的模型在这份<b>已授权</b>的候选集内选择并抽参，第二跳凭 {@code toolRef} 执行。
 * 这与网关的核心原则完全一致：网关给候选，别人的模型选，网关确定性复核。</p>
 *
 * <p>回传载荷里出现的只有 {@code toolRef} 这类短别名，真实 {@code capabilityId}、协议绑定、
 * 服务地址与接口名一概不出现——它们由 {@code AgentCapabilityResolver} 的投影阶段剔除，
 * 本类不做任何补充，也因此不可能把它们重新引入。</p>
 *
 * <p>空候选集不回传 {@code INPUT_REQUIRED}：一个「需要补充输入」却没给出任何可选项的响应，
 * 会让对端反复重试同一条永远无解的路径。这种情况映射为带稳定错误码的失败态。</p>
 *
 * <p>本类不可变且线程安全。</p>
 *
 * @author cmiracle@163.com
 * @since 0.1.0
 */
public final class A2aDelegatedRetrievalHandler implements A2aRetrievalHandler {

    /** 当前身份下没有任何可用能力时的稳定错误码：与「无权限」共用同一语义，不可枚举。 */
    public static final String NO_CAPABILITY_CODE = "NO_CAPABILITY_AVAILABLE";

    /** 回传载荷里承载候选集的字段名。 */
    static final String FIELD_CANDIDATES = "candidates";

    /** 回传载荷里承载第二跳消息形态的字段名。 */
    static final String FIELD_ARGUMENT_CONTRACT = "argumentContract";

    /** 回传载荷里承载候选集有效期的字段名。 */
    static final String FIELD_EXPIRES_AT = "expiresAt";

    private final AgentHostConnector connector;
    private final A2aTaskStateMapper stateMapper;
    private final int topK;

    /**
     * @param connector   协议中立的 Agent 回合连接器，不能为 {@code null}
     * @param stateMapper 状态映射器，不能为 {@code null}
     * @param topK        回传的候选数量上限，必须为正
     */
    public A2aDelegatedRetrievalHandler(AgentHostConnector connector,
                                        A2aTaskStateMapper stateMapper,
                                        int topK) {
        this.connector = Objects.requireNonNull(connector, "connector must not be null");
        this.stateMapper = Objects.requireNonNull(stateMapper, "stateMapper must not be null");
        if (topK <= 0) {
            throw new IllegalArgumentException("topK must be positive");
        }
        this.topK = topK;
    }

    /**
     * 执行确定性检索并回传候选集。
     *
     * <p>{@code agentTurnId} 直接绑定 A2A 的 {@code taskId}：第 1 跳签发的 {@code toolRef}
     * 存活于该 turn，第 2 跳凭同一 {@code taskId} 引用，超出 turn 或超时即失效。
     * 这让「候选句柄的生命周期」复用既有回合记忆，而不需要 A2A 侧另建一套过期机制。</p>
     *
     * @param request 首跳请求
     * @return 候选集回传任务，或带稳定错误码的失败态任务
     */
    @Override
    public Task handle(Request request) {
        Objects.requireNonNull(request, "request must not be null");
        AgentHostConnector.ResolveResult resolved = connector.resolve(
                request.requestContext(), request.taskContext().taskId(),
                request.requestId(), request.query(), topK);
        AgentCapabilityResolver.Resolution resolution = resolved.resolution();
        if (resolution.status() != AgentCapabilityResolver.Status.RESOLVED) {
            return stateMapper.toTask(request.taskContext(), error(resolution.errorCode()));
        }
        if (resolution.candidates().isEmpty()) {
            // 「无权限」与「无匹配」共用同一错误码：区分开来就等于让对端可以探测能力面的存在与否。
            return stateMapper.toTask(request.taskContext(), error(NO_CAPABILITY_CODE));
        }
        return stateMapper.toCandidateTask(request.taskContext(), payload(resolution));
    }

    /** 组装候选集回传载荷。 */
    private static Map<String, Object> payload(AgentCapabilityResolver.Resolution resolution) {
        List<Map<String, Object>> candidates =
                new ArrayList<>(resolution.candidates().size());
        for (AgentCapabilityResolver.Candidate candidate : resolution.candidates()) {
            candidates.add(candidate(candidate));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(FIELD_CANDIDATES, List.copyOf(candidates));
        payload.put(FIELD_ARGUMENT_CONTRACT, secondHopContract());
        if (resolution.expiresAt() != null) {
            payload.put(FIELD_EXPIRES_AT, resolution.expiresAt().toString());
        }
        return payload;
    }

    /**
     * 单个候选的对外形态。
     *
     * <p>只保留对端做选择与抽参真正需要的字段。{@code schemaClass} 这类内部分类不出境：
     * 它描述的是网关如何加载入参 Schema，对选择决策没有帮助，却会透露实现细节。</p>
     */
    private static Map<String, Object> candidate(AgentCapabilityResolver.Candidate candidate) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put(A2aTaskRequest.FIELD_TOOL_REF, candidate.toolRef());
        item.put("name", candidate.displayName());
        item.put("purpose", candidate.purpose());
        if (candidate.executionMode() != null) {
            item.put("executionMode", candidate.executionMode());
        }
        item.put(FIELD_ARGUMENT_CONTRACT, candidate.argumentContract() == null
                ? Map.of() : candidate.argumentContract());
        return item;
    }

    /**
     * 第二跳消息的形态说明。
     *
     * <p>用协议内建方式告知对端「补齐输入后怎么再发一跳」，避免对端只能靠文档约定推断字段名。
     * 这份说明是<b>静态</b>的：第二跳的信封形态与具体能力无关，随能力变化的是各候选自带的
     * {@code argumentContract}。</p>
     */
    private static Map<String, Object> secondHopContract() {
        return Map.of(
                A2aTaskRequest.FIELD_TOOL_REF, "string: one of candidates[].toolRef",
                A2aTaskRequest.FIELD_ARGUMENTS,
                "object: matching the chosen candidate's argumentContract");
    }

    private static ModelResult error(String errorCode) {
        return new ModelResult(ModelResult.Status.ERROR, null, errorCode, null, null, null);
    }
}
