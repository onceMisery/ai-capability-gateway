package com.ai.gateway.adapter.a2a;

import com.ai.gateway.application.agent.AgentModelResultMapper.ModelResult;
import com.ai.gateway.domain.model.A2aTaskContext;
import io.a2a.spec.Artifact;
import io.a2a.spec.DataPart;
import io.a2a.spec.Message;
import io.a2a.spec.Part;
import io.a2a.spec.Task;
import io.a2a.spec.TaskState;
import io.a2a.spec.TaskStatus;
import io.a2a.spec.TextPart;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 把网关的执行结果映射成 A2A 的 {@link Task}（设计 §3.5）。
 *
 * <p>本类是「网关状态 → A2A 状态」的<b>唯一</b>转换点，且不做任何策略判断：状态由上游确定，
 * 这里只负责用协议原生类型把它表达出来。三条硬约束刻在类型与实现里：</p>
 * <ol>
 * <li><b>{@code confirmationToken} 不进入任何 A2A 消息</b>：待确认响应只带 {@code operationId}
 * 与过期时刻，token 留在网关侧的待确认存储里。为了让这条约束不依赖调用方自觉，
 * 每个即将出站的数据段都会被递归检查一遍，命中即失效关闭（抛异常），而不是悄悄剔除——
 * 悄悄剔除会把一个必须修的缺陷永久隐藏起来。</li>
 * <li><b>拒绝原因不可枚举</b>：{@link #rejected(A2aTaskContext)} 在签名上就没有承载原因的位置，
 * 因此注入命中、跳数超限与越权在对端看来完全一致。真实原因只写审计。</li>
 * <li><b>不透传上游措辞</b>：文本段一律使用固定外部措辞，Provider 与 LLM 的原始错误信息
 * 不进入协议消息。</li>
 * </ol>
 *
 * <p>时间戳来自注入的 {@link Clock}，标识由 {@code taskId} 派生而非随机生成：
 * 同一次任务重复映射得到等价的消息，重试与断言都因此可预期。</p>
 *
 * <p>本类不可变且线程安全。</p>
 *
 * @author cmiracle@163.com
 * @since 0.1.0
 */
public final class A2aTaskStateMapper {

    /** 统一拒绝错误码：不区分注入命中、跳数超限与越权，避免对端据此枚举网关内部判定。 */
    public static final String REASON_REJECTED = "A2A_REQUEST_REJECTED";

    /** peer 未注册为受信、无法承载写确认时的稳定错误码。 */
    public static final String REASON_AUTH_REQUIRED = "A2A_PEER_NOT_TRUSTED";

    /** 上游未给出错误码时使用的兜底码，避免出站消息里出现空错误码。 */
    public static final String FALLBACK_ERROR_CODE = "GATEWAY_ERROR";

    private static final String FIELD_STATUS = "status";
    private static final String FIELD_ERROR_CODE = "errorCode";
    private static final String FIELD_DATA = "data";
    private static final String FIELD_OPERATION_ID = "operationId";
    private static final String FIELD_EXPIRES_AT = "expiresAt";
    private static final String FIELD_CONFIRMATION = "confirmation";

    /**
     * 绝不允许出现在出站数据段里的字段名（比较时统一去掉分隔符并转小写）。
     *
     * <p>只收敛确认令牌一族：把范围放宽到「像密钥的字段名」会误伤合法业务字段，
     * 而真正的输出脱敏由 {@code RedactionService} 在更上游完成，这里是最后一道断言。</p>
     */
    private static final Set<String> FORBIDDEN_KEYS = Set.of("confirmationtoken");

    /** 递归检查的节点预算：超出即视为无法验证，按失效关闭处理。 */
    private static final int MAX_SCAN_NODES = 50_000;

    private final Clock clock;

    /** 使用系统 UTC 时钟。 */
    public A2aTaskStateMapper() {
        this(Clock.systemUTC());
    }

    /**
     * @param clock 生成 {@link TaskStatus#timestamp()} 的时钟，不能为 {@code null}
     */
    public A2aTaskStateMapper(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * 把一次能力调用的结果映射为终态或待确认态的 {@link Task}。
     *
     * <p>三种结果状态各自对应设计 §3.5 表格里的一行：完成 → {@link TaskState#COMPLETED}，
     * 待确认 → {@link TaskState#INPUT_REQUIRED}，失败 → {@link TaskState#FAILED}。
     * 注意失败不映射为 {@link TaskState#REJECTED}：拒绝表示「这个请求不该被受理」，
     * 而失败表示「受理了但没成功」，两者对调用方的下一步动作含义完全不同。</p>
     *
     * @param context 任务上下文，不能为 {@code null}
     * @param result  网关执行结果，不能为 {@code null}
     * @return 对应的 A2A 任务
     */
    public Task toTask(A2aTaskContext context, ModelResult result) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(result, "result must not be null");
        return switch (result.status()) {
            case COMPLETED -> completed(context, result);
            case CONFIRMATION_REQUIRED -> confirmationRequired(context, result, Map.of());
            case ERROR -> failed(context, result);
        };
    }

    /**
     * 映射待确认结果，并附带一份<b>已在上游脱敏</b>的确认摘要。
     *
     * <p>摘要由调用方提供而不是在这里拼装：确认摘要的原始形态含接口名、方法与序列化方式，
     * 这些字段一律不得出境，因此「哪些字段可以出境」必须是一个显式的上游决定，
     * 而不是本类顺手从领域对象里挑几个字段的结果。</p>
     *
     * @param context          任务上下文，不能为 {@code null}
     * @param result           网关执行结果，状态必须为待确认
     * @param redactedSummary  已脱敏的确认摘要，{@code null} 视为空
     * @return 状态为 {@link TaskState#INPUT_REQUIRED} 的任务
     */
    public Task toConfirmationTask(A2aTaskContext context, ModelResult result,
                                   Map<String, Object> redactedSummary) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(result, "result must not be null");
        if (result.status() != ModelResult.Status.CONFIRMATION_REQUIRED) {
            throw new IllegalArgumentException(
                    "result status must be CONFIRMATION_REQUIRED, was " + result.status());
        }
        return confirmationRequired(context, result,
                redactedSummary == null ? Map.of() : redactedSummary);
    }

    /**
     * 映射首跳的候选集回传（设计 §3.4.1 委派选择档）。
     *
     * <p>候选集用 {@link TaskState#INPUT_REQUIRED} 而不是自定义状态表达：对端只需按协议
     * 「补充输入后用同一 {@code taskId} 再发一跳」，无需理解任何网关私有约定。</p>
     *
     * @param context 任务上下文，不能为 {@code null}
     * @param payload 候选集与入参契约，不能为空
     * @return 状态为 {@link TaskState#INPUT_REQUIRED} 的任务
     */
    public Task toCandidateTask(A2aTaskContext context, Map<String, Object> payload) {
        Objects.requireNonNull(context, "context must not be null");
        if (payload == null || payload.isEmpty()) {
            throw new IllegalArgumentException("candidate payload must not be empty");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(FIELD_STATUS, TaskState.INPUT_REQUIRED.asString());
        data.putAll(payload);
        return statusOnlyTask(context, TaskState.INPUT_REQUIRED,
                Wording.INPUT_REQUIRED.text, data);
    }

    /**
     * 映射拒绝：不可枚举，且在签名上没有承载具体原因的位置。
     *
     * @param context 任务上下文，不能为 {@code null}
     * @return 状态为 {@link TaskState#REJECTED} 的任务
     */
    public Task rejected(A2aTaskContext context) {
        Objects.requireNonNull(context, "context must not be null");
        return statusOnlyTask(context, TaskState.REJECTED, Wording.REJECTED.text,
                Map.of(FIELD_STATUS, TaskState.REJECTED.asString(),
                        FIELD_ERROR_CODE, REASON_REJECTED));
    }

    /**
     * 映射「需要更强的身份」：peer 尚未注册为受信，因此无法承载写操作的确认。
     *
     * <p>这一档与拒绝分开，是因为它对调用方是<b>可行动</b>的信息（去完成注册），
     * 而且不透露任何能力面的存在与否。</p>
     *
     * @param context 任务上下文，不能为 {@code null}
     * @return 状态为 {@link TaskState#AUTH_REQUIRED} 的任务
     */
    public Task authRequired(A2aTaskContext context) {
        Objects.requireNonNull(context, "context must not be null");
        return statusOnlyTask(context, TaskState.AUTH_REQUIRED, Wording.AUTH_REQUIRED.text,
                Map.of(FIELD_STATUS, TaskState.AUTH_REQUIRED.asString(),
                        FIELD_ERROR_CODE, REASON_AUTH_REQUIRED));
    }

    /** 完成态：业务结果放入 Artifact，文本段只给固定措辞。 */
    private Task completed(A2aTaskContext context, ModelResult result) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(FIELD_STATUS, TaskState.COMPLETED.asString());
        data.put(FIELD_DATA, result.data() == null ? Map.of() : result.data());
        assertNoForbiddenField(data);
        Artifact artifact = new Artifact.Builder()
                .artifactId(context.taskId() + "-result")
                .name("gateway-result")
                .parts(List.of(new TextPart(Wording.COMPLETED.text), new DataPart(data)))
                .build();
        return new Task.Builder()
                .id(context.taskId())
                .contextId(context.contextId())
                .status(new TaskStatus(TaskState.COMPLETED, null, now()))
                .artifacts(List.of(artifact))
                .build();
    }

    /** 待确认态：只带 operationId 与过期时刻，绝不带 confirmationToken。 */
    private Task confirmationRequired(A2aTaskContext context, ModelResult result,
                                      Map<String, Object> redactedSummary) {
        if (result.operationId() == null || result.operationId().isBlank()) {
            // 缺了 operationId 的待确认响应无法被后续确认跳引用，等于一个永远悬挂的任务。
            throw new IllegalArgumentException("operationId is required for confirmation");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(FIELD_STATUS, TaskState.INPUT_REQUIRED.asString());
        data.put(FIELD_OPERATION_ID, result.operationId());
        data.put(FIELD_EXPIRES_AT, result.expiresAt() == null
                ? "" : result.expiresAt().toString());
        if (!redactedSummary.isEmpty()) {
            data.put(FIELD_CONFIRMATION, redactedSummary);
        }
        return statusOnlyTask(context, TaskState.INPUT_REQUIRED,
                Wording.INPUT_REQUIRED.text, data);
    }

    /** 失败态：错误码可以出境，上游措辞不可以。 */
    private Task failed(A2aTaskContext context, ModelResult result) {
        String errorCode = result.errorCode() == null || result.errorCode().isBlank()
                ? FALLBACK_ERROR_CODE : result.errorCode();
        return statusOnlyTask(context, TaskState.FAILED, Wording.FAILED.text,
                Map.of(FIELD_STATUS, TaskState.FAILED.asString(),
                        FIELD_ERROR_CODE, errorCode));
    }

    /**
     * 构造一个只带状态消息、不带 Artifact 的任务。
     *
     * <p>非完成态没有业务产物，把说明放进 {@code status.message} 而不是造一个空 Artifact：
     * 空 Artifact 会被对端当作「有产物但内容为空」，那是与事实不同的信息。</p>
     */
    private Task statusOnlyTask(A2aTaskContext context, TaskState state, String text,
                                Map<String, Object> data) {
        assertNoForbiddenField(data);
        List<Part<?>> parts = List.of(new TextPart(text), new DataPart(data));
        Message message = new Message.Builder()
                .role(Message.Role.AGENT)
                .parts(parts)
                .messageId(context.taskId() + "-" + state.asString())
                .contextId(context.contextId())
                .taskId(context.taskId())
                .build();
        return new Task.Builder()
                .id(context.taskId())
                .contextId(context.contextId())
                .status(new TaskStatus(state, message, now()))
                .build();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    /**
     * 递归断言出站数据段里不存在确认令牌字段。
     *
     * <p>命中即抛异常而不是剔除：能走到这里说明上游已经把 token 放进了可出境的结构，
     * 那是一个必须被修的缺陷；静默剔除会让它在下一次结构调整时重新变成真实泄露。</p>
     *
     * @throws IllegalStateException 命中禁止字段，或结构规模超出可验证预算
     */
    private static void assertNoForbiddenField(Map<String, Object> data) {
        Deque<Object> pending = new ArrayDeque<>();
        pending.push(data);
        int visited = 0;
        while (!pending.isEmpty()) {
            if (++visited > MAX_SCAN_NODES) {
                throw new IllegalStateException(
                        "outbound A2A payload is too large to verify against forbidden fields");
            }
            Object current = pending.pop();
            if (current instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (entry.getKey() instanceof String key && isForbidden(key)) {
                        throw new IllegalStateException(
                                "confirmationToken must never enter an A2A message");
                    }
                    if (entry.getValue() != null) {
                        pending.push(entry.getValue());
                    }
                }
            } else if (current instanceof Collection<?> collection) {
                for (Object item : collection) {
                    if (item != null) {
                        pending.push(item);
                    }
                }
            }
        }
    }

    /** 归一化字段名后比对：去掉下划线与连字符并转小写，避免换个写法就绕过断言。 */
    private static boolean isForbidden(String key) {
        String normalized = key.toLowerCase(Locale.ROOT)
                .replace("_", "").replace("-", "");
        return FORBIDDEN_KEYS.contains(normalized);
    }

    /** 固定外部措辞：Provider 与 LLM 的原始信息一律不进入协议消息。 */
    private enum Wording {

        COMPLETED("Completed"),
        INPUT_REQUIRED("Additional input required"),
        AUTH_REQUIRED("Peer is not registered as trusted"),
        REJECTED("Request rejected"),
        FAILED("Request failed");

        private final String text;

        Wording(String text) {
            this.text = text;
        }
    }
}
