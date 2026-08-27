package com.ai.gateway.adapter.a2a;

import com.ai.gateway.domain.model.A2aTaskContext;
import com.ai.gateway.domain.model.RequestContext;
import io.a2a.spec.Task;

import java.util.Objects;

/**
 * 首跳自由文本 / 检索请求的处理策略（设计 §3.4.1）。
 *
 * <p>抽出这层接口的目的是让「选择权归谁」成为一个可替换的装配决定，而不是散落在传输适配器里的
 * 若干分支。三档选择模式对应的处理方式差异极大——
 * {@link A2aSelectionMode#DELEGATED_SELECTION} 全程零模型调用、把候选集回传给对端；
 * {@link A2aSelectionMode#GATEWAY_SELECTION} 在网关内部复用 NL 路由内核完成一跳；
 * {@link A2aSelectionMode#STRUCTURED_ONLY} 根本不受理自由文本。若把它们写成
 * {@link A2aServerTransportAdapter} 内部的 {@code switch}，每新增一档都要改动传输层，
 * 而传输层与「选择归属」本来无关。</p>
 *
 * <p>实现方必须返回一个<b>完整可回复</b>的 {@link Task}：状态、消息与产物都已就绪。
 * 传输适配器只根据 {@code status.state()} 决定审计事件类型，不再改写载荷。</p>
 *
 * @author cmiracle@163.com
 * @since 0.1.0
 */
@FunctionalInterface
public interface A2aRetrievalHandler {

    /**
     * 处理一次首跳检索请求。
     *
     * @param request 请求，恒不为 {@code null}
     * @return 可直接回复对端的任务
     */
    Task handle(Request request);

    /**
     * 返回一个恒拒绝的处理策略。
     *
     * <p>供 {@link A2aSelectionMode#STRUCTURED_ONLY} 装配使用。该档下策略执行点已经在准入阶段
     * 拒掉了自由文本形态，因此本策略<b>正常情况下不可达</b>；它的存在是为了让「不受理自由文本」
     * 这件事在装配上有一个明确的落点，而不是留一个 {@code null} 让传输适配器在运行时才
     * 空指针失败——一个只在特定档位、特定载荷下才触发的空指针，等于把配置错误推迟到
     * 生产流量上暴露。</p>
     *
     * @param stateMapper 状态映射器，不能为 {@code null}
     * @return 恒返回 {@code rejected} 状态的策略
     */
    static A2aRetrievalHandler rejecting(A2aTaskStateMapper stateMapper) {
        Objects.requireNonNull(stateMapper, "stateMapper must not be null");
        return request -> stateMapper.rejected(request.taskContext());
    }

    /**
     * 首跳检索请求。
     *
     * <p>携带 {@link A2aPolicyEnforcementFilter.Decision} 而不是仅携带身份，是因为实现方
     * 可能需要信任分级来决定候选集的裁剪程度；但它<b>不得</b>据此重做任何准入判断——
     * 准入已经在策略执行点完成，重做一遍就会产生两处判定不一致的缺口。</p>
     *
     * @param requestContext 入站请求上下文，不能为 {@code null}
     * @param taskContext    任务上下文，不能为 {@code null}
     * @param decision       策略执行点的准入判定，不能为 {@code null}
     * @param query          检索文本，不能为 {@code null}
     * @param requestId      本次请求的关联标识，不能为 {@code null}
     */
    record Request(RequestContext requestContext,
                   A2aTaskContext taskContext,
                   A2aPolicyEnforcementFilter.Decision decision,
                   String query,
                   String requestId) {

        /**
         * 紧凑构造器。
         *
         * @param requestContext 入站请求上下文
         * @param taskContext    任务上下文
         * @param decision       准入判定
         * @param query          检索文本
         * @param requestId      关联标识
         */
        public Request {
            Objects.requireNonNull(requestContext, "requestContext must not be null");
            Objects.requireNonNull(taskContext, "taskContext must not be null");
            Objects.requireNonNull(decision, "decision must not be null");
            Objects.requireNonNull(query, "query must not be null");
            Objects.requireNonNull(requestId, "requestId must not be null");
        }
    }
}
