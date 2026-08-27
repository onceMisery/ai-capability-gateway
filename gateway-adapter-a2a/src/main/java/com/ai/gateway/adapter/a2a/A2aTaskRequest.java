package com.ai.gateway.adapter.a2a;

import io.a2a.spec.DataPart;
import io.a2a.spec.Message;
import io.a2a.spec.Part;
import io.a2a.spec.TextPart;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 一次入站 A2A {@link Message} 在网关侧的<b>意图分类</b>结果。
 *
 * <p>A2A 的 Task 语义刻意不透明：协议只保证「消息由若干 Part 组成」，不规定网关该做什么。
 * 因此必须有一个确定性的分类步骤，把消息映射到网关的三条既有路径之一。分类只看载荷形态，
 * <b>不做任何策略判断</b>——是否受理由 {@link A2aPolicyEnforcementFilter} 决定，
 * 本记录只回答「对端想做什么」。</p>
 *
 * <p>四类意图及其判定优先级：</p>
 * <ol>
 * <li>{@link Kind#CONFIRMATION}——结构化段含 {@code operationId}。优先级最高：
 * 待确认操作的第二跳一旦被误分类成新请求，就会产生一次重复的写准备。</li>
 * <li>{@link Kind#TOOL_INVOCATION}——结构化段含 {@code toolRef}（首跳回传的可执行句柄）。</li>
 * <li>{@link Kind#RETRIEVAL}——结构化段含 {@code query}，或消息含 {@link TextPart}。
 * 走确定性检索并回传候选集。</li>
 * <li>{@link Kind#MALFORMED}——三者皆无。不猜测意图：猜错等于凭对端的一段随意载荷
 * 选出一个网关自己都不确定的动作。</li>
 * </ol>
 *
 * <p><b>可执行句柄只认 {@code toolRef}，不认 AgentCard 上的 {@code skillId}。</b>
 * 卡片上的 skill 粒度是业务域（{@code domain.<域>}），刻意不是能力句柄——若把它当作可执行标识，
 * 「真实 capabilityId 不出现在任何投影字段」这条约束就失去意义，而域到能力的选择也会
 * 从网关的确定性检索退化成对端的字符串拼装。可执行的 {@code toolRef} 一律由检索回传签发，
 * 并绑定在该 turn 上。</p>
 *
 * @param kind        意图分类
 * @param query       检索用的自然语言文本，仅 {@link Kind#RETRIEVAL} 有值
 * @param toolRef     首跳签发的能力句柄，仅 {@link Kind#TOOL_INVOCATION} 有值
 * @param arguments   调用入参，恒不为 {@code null}
 * @param operationId 待确认操作标识，仅 {@link Kind#CONFIRMATION} 有值
 * @param texts       消息里所有需要做注入检测的字符串片段
 * @param structured  消息是否为结构化形态（含 {@link DataPart}）
 * @author cmiracle@163.com
 * @since 0.1.0
 */
public record A2aTaskRequest(Kind kind,
                             String query,
                             String toolRef,
                             Map<String, Object> arguments,
                             String operationId,
                             List<String> texts,
                             boolean structured) {

    /** 结构化段里承载能力句柄的字段名。 */
    public static final String FIELD_TOOL_REF = "toolRef";

    /** 结构化段里承载入参的字段名。 */
    public static final String FIELD_ARGUMENTS = "arguments";

    /** 结构化段里承载待确认操作标识的字段名。 */
    public static final String FIELD_OPERATION_ID = "operationId";

    /** 结构化段里承载检索文本的字段名。 */
    public static final String FIELD_QUERY = "query";

    /** 单条入站文本参与注入检测的长度上限：超长片段按前缀截断后仍然参与检测。 */
    private static final int MAX_SCANNED_TEXT_LENGTH = 8 * 1024;

    /** 参与注入检测的片段数量上限，避免对端用海量小片段放大扫描成本。 */
    private static final int MAX_SCANNED_TEXTS = 64;

    /**
     * 紧凑构造器：冻结集合。
     *
     * @param kind        意图分类，不能为 {@code null}
     * @param query       检索文本
     * @param toolRef     能力句柄
     * @param arguments   调用入参
     * @param operationId 待确认操作标识
     * @param texts       待检测文本
     * @param structured  是否结构化形态
     */
    public A2aTaskRequest {
        Objects.requireNonNull(kind, "kind must not be null");
        // 用 unmodifiableMap 而不是 Map.copyOf：JSON 的 null 值是合法入参形态，
        // 而 Map.copyOf 会对它抛 NPE，把一次本该由 Schema 确定性拒绝的请求
        // 变成传输层的意外异常。
        arguments = arguments == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
        texts = texts == null ? List.of() : List.copyOf(texts);
    }

    /**
     * 从入站消息解析意图。
     *
     * @param message 入站消息，允许为 {@code null}
     * @return 意图分类结果；{@code null} 消息返回 {@link Kind#MALFORMED}
     */
    public static A2aTaskRequest from(Message message) {
        if (message == null || message.getParts() == null) {
            return malformed(false);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        StringBuilder freeText = new StringBuilder();
        boolean structured = false;
        for (Part<?> part : message.getParts()) {
            if (part instanceof DataPart dataPart) {
                structured = true;
                if (dataPart.getData() != null) {
                    // 多个 DataPart 按出现顺序合并：后出现的同名字段覆盖先出现的，
                    // 与单个 JSON 对象里重复键的解析结果保持一致。
                    data.putAll(dataPart.getData());
                }
            } else if (part instanceof TextPart textPart && textPart.getText() != null) {
                if (freeText.length() > 0) {
                    freeText.append('\n');
                }
                freeText.append(textPart.getText());
            }
        }
        List<String> texts = scannableTexts(freeText.toString(), data);
        String operationId = text(data.get(FIELD_OPERATION_ID));
        if (operationId != null) {
            return new A2aTaskRequest(Kind.CONFIRMATION, null, null, Map.of(),
                    operationId, texts, structured);
        }
        String toolRef = text(data.get(FIELD_TOOL_REF));
        if (toolRef != null) {
            return new A2aTaskRequest(Kind.TOOL_INVOCATION, null, toolRef,
                    arguments(data.get(FIELD_ARGUMENTS)), null, texts, structured);
        }
        String query = text(data.get(FIELD_QUERY));
        if (query == null && !freeText.isEmpty()) {
            query = freeText.toString();
        }
        if (query != null) {
            return new A2aTaskRequest(Kind.RETRIEVAL, query, null, Map.of(),
                    null, texts, structured);
        }
        // 形态不可判定时仍然带上待检测文本：注入检测在策略执行点先于意图分派发生，
        // 保留片段让「对端投了一段注入载荷」被记成注入而不是笼统的「意图不可判定」。
        return new A2aTaskRequest(Kind.MALFORMED, null, null, Map.of(),
                null, texts, structured);
    }

    /**
     * 转换为策略执行点的入站请求描述。
     *
     * <p>形态判定以「消息里是否出现 {@link DataPart}」为准，而不是以意图分类为准：
     * {@code STRUCTURED_ONLY} 档要拒绝的是<b>自由文本首跳</b>这种形态，
     * 而一个结构化的检索请求（{@code DataPart{query}}）在该档下是合法的。</p>
     *
     * @return 策略执行点可判定的入站请求
     */
    public A2aPolicyEnforcementFilter.InboundRequest toInboundRequest() {
        return structured
                ? A2aPolicyEnforcementFilter.InboundRequest.structured(texts)
                : new A2aPolicyEnforcementFilter.InboundRequest(
                        A2aPolicyEnforcementFilter.InboundRequest.Shape.FREE_TEXT, texts);
    }

    /**
     * 收集需要做注入检测的字符串片段。
     *
     * <p>结构化段里的字符串同样参与检测：{@code DataPart} 的字段值一样会被下游拼进提示或日志，
     * 「结构化」只描述形态，不代表内容可信。</p>
     */
    private static List<String> scannableTexts(String freeText, Map<String, Object> data) {
        List<String> texts = new ArrayList<>();
        if (!freeText.isEmpty()) {
            texts.add(truncate(freeText));
        }
        collectStrings(data, texts);
        return texts;
    }

    /** 递归收集字符串值；达到片段上限即停止，剩余片段由更上游的体积限制拦截。 */
    private static void collectStrings(Object value, List<String> sink) {
        if (sink.size() >= MAX_SCANNED_TEXTS) {
            return;
        }
        if (value instanceof String text) {
            if (!text.isBlank()) {
                sink.add(truncate(text));
            }
        } else if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() instanceof String key) {
                    collectStrings(key, sink);
                }
                collectStrings(entry.getValue(), sink);
            }
        } else if (value instanceof Iterable<?> items) {
            for (Object item : items) {
                collectStrings(item, sink);
            }
        }
    }

    private static String truncate(String text) {
        return text.length() <= MAX_SCANNED_TEXT_LENGTH
                ? text : text.substring(0, MAX_SCANNED_TEXT_LENGTH);
    }

    /** 读取字符串字段；空白视为缺失，避免空串被当作有效句柄。 */
    private static String text(Object value) {
        if (!(value instanceof String text) || text.isBlank()) {
            return null;
        }
        return text.trim();
    }

    /**
     * 读取入参映射；非映射形态视为空入参，由下游 Schema 校验给出确定性拒绝。
     *
     * <p>只保留字符串键：JSON 对象的键必然是字符串，但本方法接到的是反序列化后的
     * {@code Object}，声明成 {@code Map<String, Object>} 却塞进非字符串键，
     * 会把一次本该被 Schema 确定性拒绝的请求变成下游某处的 {@code ClassCastException}。</p>
     */
    private static Map<String, Object> arguments(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> arguments = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() instanceof String key) {
                arguments.put(key, entry.getValue());
            }
        }
        return arguments;
    }

    private static A2aTaskRequest malformed(boolean structured) {
        return new A2aTaskRequest(Kind.MALFORMED, null, null, Map.of(),
                null, List.of(), structured);
    }

    /** 入站意图分类。 */
    public enum Kind {

        /** 确定性检索并回传候选集。 */
        RETRIEVAL,

        /** 凭首跳签发的 {@code toolRef} 执行一次能力调用。 */
        TOOL_INVOCATION,

        /** 确认一次已准备好的写操作。 */
        CONFIRMATION,

        /** 载荷不足以确定意图。 */
        MALFORMED
    }
}
