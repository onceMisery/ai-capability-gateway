package com.ai.gateway.domain.model;

/**
 * 审计与埋点的「执行平面」标签。
 *
 * <p>网关存在多个协议入口，它们共用同一条确定性执行链，但成本归属、Prompt 版本语义、
 * 限流口径和故障率口径各不相同。若不区分平面，Agent 平面的问题会被运行面
 * 自然语言链路的指标掩盖，曝光策略判据（如“NL 请求占运行面总量 &lt; 1%”）也无法成立。</p>
 *
 * <p>取值刻意使用连字符小写形式，与既有审计 JSON 与监控标签风格一致，并保证枚举
 * 重命名不会改变对外可观测契约。</p>
 *
 * @since 0.2.0
 */
public enum AuditPlane {

    /** 运行面自然语言路由（瘦客户端兼容面）。 */
    GATEWAY_NL("gateway-nl"),

    /** 管理面能力目录诊断（dry-run，不产生业务副作用）。 */
    GATEWAY_NL_DIAGNOSTIC("gateway-nl-diagnostic"),

    /** Agent Host 平面（受信 Host 经 AgentHostConnector 调用）。 */
    AGENT_HOST("agent-host"),

    /** A2A 入站平面。 */
    A2A_INBOUND("a2a-inbound"),

    /** MCP 入口平面。 */
    MCP("mcp"),

    /** 结构化工具直调等其他运行面入口。 */
    STRUCTURED("structured");

    /** 审计 JSON 与监控标签使用的字段名。 */
    public static final String FIELD = "plane";

    private final String wireValue;

    AuditPlane(String wireValue) {
        this.wireValue = wireValue;
    }

    /**
     * @return 对外可观测契约中的稳定取值
     */
    public String wireValue() {
        return wireValue;
    }

    /**
     * 构造仅含平面标签的审计明细 JSON。
     *
     * <p>取值全部来自枚举常量，不含外部输入，因此手工拼接是安全的，
     * 领域层也无需引入 JSON 库。</p>
     *
     * @return 形如 <code>{"plane":"gateway-nl"}</code> 的 JSON 文本
     */
    public String detailsJson() {
        return "{\"" + FIELD + "\":\"" + wireValue + "\"}";
    }

    /**
     * 构造「平面标签 + 单个文本字段」的审计明细 JSON。
     *
     * <p>字段值可能来自协议适配器（如 {@code protocolStatus}），因此统一做 JSON 转义，
     * 避免下游解析器把审计明细读成结构不完整的 JSON。这里刻意不引入 JSON 库：
     * 领域层保持零依赖，而审计明细的结构是固定的扁平对象。</p>
     *
     * @param fieldName 字段名，必须是代码内的常量
     * @param textValue 字段值；{@code null} 视为缺省，退化为只含平面标签
     * @return 形如 <code>{"plane":"mcp","reason":"..."}</code> 的 JSON 文本
     */
    public String detailsJson(String fieldName, String textValue) {
        if (fieldName == null || fieldName.isBlank() || textValue == null) {
            return detailsJson();
        }
        return detailsJsonWithRawFields(
                "\"" + escape(fieldName) + "\":\"" + escape(textValue) + "\"");
    }

    /**
     * 构造「平面标签 + 调用方自备字段」的审计明细 JSON。
     *
     * <p>供数值等非字符串字段使用（如 {@code "snapshotVersion":7}）。参数被原样拼入结果，
     * 因此<strong>只允许传入代码内构造的字面量或数值</strong>，不得传入外部输入。
     * 需要写入外部来源的文本时请使用 {@link #detailsJson(String, String)}。</p>
     *
     * @param rawFields 形如 {@code "snapshotVersion":7} 的字段片段，可为空
     * @return 合并了平面标签的 JSON 文本
     */
    public String detailsJsonWithRawFields(String rawFields) {
        String fields = rawFields == null ? "" : rawFields.trim();
        // 兼容 {"a":1} 与 "a":1 两种写法，便于既有调用点直接迁移。
        if (fields.startsWith("{") && fields.endsWith("}")) {
            fields = fields.substring(1, fields.length() - 1).trim();
        }
        if (fields.isEmpty()) {
            return detailsJson();
        }
        return "{\"" + FIELD + "\":\"" + wireValue + "\"," + fields + "}";
    }

    /** 转义 JSON 字符串字面量中必须处理的字符。 */
    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (c < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.toString();
    }
}
