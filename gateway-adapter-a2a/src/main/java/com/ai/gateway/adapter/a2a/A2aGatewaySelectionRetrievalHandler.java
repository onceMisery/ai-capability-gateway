package com.ai.gateway.adapter.a2a;

import com.ai.gateway.application.agent.AgentModelResultMapper.ModelResult;
import com.ai.gateway.application.runtime.NaturalLanguageQueryUseCase;
import io.a2a.spec.Task;

import java.util.Map;
import java.util.Objects;

/**
 * 兼容档 {@link A2aSelectionMode#GATEWAY_SELECTION} 的首跳处理：网关内部完成受限选择，一跳返回结果
 * （设计 §3.4.1）。
 *
 * <p>这一档服务于两类现实调用方：想用 A2A 客户端库「发一句话就拿结果」的传统微服务，
 * 以及不愿实现两跳交互的第三方 Agent。它<b>显式依赖 NL 路由内核</b>，因此
 * {@code gateway.a2a.selection-mode=GATEWAY_SELECTION} 与
 * {@code gateway.runtime.nl-router.mode=DISABLED} 是一组必须在启动期被拒绝的配置组合；
 * 本类不重复该判断——内核未曝光时 {@link NaturalLanguageQueryUseCase} 本身会返回稳定错误码，
 * 在适配层再加一道判断只会产生两处可能不一致的闸门。</p>
 *
 * <p>两条与默认档不同的约束：</p>
 * <ol>
 * <li><b>不启用多轮澄清</b>。模型判定信息不足时，本类回传一个语义稳定的
 * {@code INPUT_REQUIRED}，要求对端把请求说得更具体后重发，而<b>不</b>引用任何澄清会话标识——
 * 澄清对话状态不回到网关，这正是方案 A 的冻结线所要求的。回传载荷里也不含模型生成的提问文本：
 * 那是模型输出，属于不可信内容，不应原样出境。</li>
 * <li><b>不承载写操作</b>。二阶段写需要一个能承担确认动作的对端，而这一档的调用方恰恰是
 * 「不愿实现第二跳」的一方。需要写的 peer 必须走默认档的两跳路径。</li>
 * </ol>
 *
 * <p>本类不可变且线程安全。</p>
 *
 * @author cmiracle@163.com
 * @since 0.1.0
 */
public final class A2aGatewaySelectionRetrievalHandler implements A2aRetrievalHandler {

    /** 回传载荷里承载「为何需要补充输入」的字段名。 */
    static final String FIELD_REASON = "reason";

    /** 回传载荷里承载「下一步该怎么做」的字段名。 */
    static final String FIELD_RETRY = "retry";

    /** 请求过于笼统、无法在候选集内确定唯一能力时的稳定原因码。 */
    public static final String REASON_AMBIGUOUS = "REQUEST_AMBIGUOUS";

    /** 稳定的重试指引：措辞固定，不含任何模型生成内容。 */
    private static final String RETRY_HINT =
            "Resend the task with a more specific request";

    private final NaturalLanguageQueryUseCase queryUseCase;
    private final A2aTaskStateMapper stateMapper;
    private final String locale;
    private final String timezone;

    /**
     * @param queryUseCase NL 路由用例，不能为 {@code null}
     * @param stateMapper  状态映射器，不能为 {@code null}
     * @param locale       请求语言标签，{@code null} 时使用 {@code zh-CN}
     * @param timezone     请求时区，{@code null} 时使用 {@code UTC}
     */
    public A2aGatewaySelectionRetrievalHandler(NaturalLanguageQueryUseCase queryUseCase,
                                               A2aTaskStateMapper stateMapper,
                                               String locale,
                                               String timezone) {
        this.queryUseCase = Objects.requireNonNull(queryUseCase, "queryUseCase must not be null");
        this.stateMapper = Objects.requireNonNull(stateMapper, "stateMapper must not be null");
        this.locale = locale == null || locale.isBlank() ? "zh-CN" : locale;
        this.timezone = timezone == null || timezone.isBlank() ? "UTC" : timezone;
    }

    /**
     * 在网关内部完成一次受限选择并返回终态。
     *
     * @param request 首跳请求
     * @return 终态任务，或语义稳定的 {@code INPUT_REQUIRED}
     */
    @Override
    public Task handle(Request request) {
        Objects.requireNonNull(request, "request must not be null");
        NaturalLanguageQueryUseCase.QueryResult result = queryUseCase.execute(
                request.requestContext(), request.requestId(), request.query(),
                locale, timezone);
        return switch (result.status()) {
            case COMPLETED -> stateMapper.toTask(request.taskContext(),
                    new ModelResult(ModelResult.Status.COMPLETED,
                            result.data(), null, null, null, null));
            case CLARIFICATION_REQUIRED -> stateMapper.toCandidateTask(
                    request.taskContext(),
                    Map.of(FIELD_REASON, REASON_AMBIGUOUS, FIELD_RETRY, RETRY_HINT));
            // 无匹配与无权限共用同一错误码：区分开来等于让对端可以探测能力面的存在与否。
            case NO_MATCH -> stateMapper.toTask(request.taskContext(),
                    error(A2aDelegatedRetrievalHandler.NO_CAPABILITY_CODE));
            case ERROR -> stateMapper.toTask(request.taskContext(),
                    error(result.errorCode()));
        };
    }

    private static ModelResult error(String errorCode) {
        return new ModelResult(ModelResult.Status.ERROR, null, errorCode, null, null, null);
    }
}
