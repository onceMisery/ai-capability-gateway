package com.ai.gateway.adapter.mcp;

import com.ai.gateway.application.agent.AgentHostConnector;
import com.ai.gateway.application.agent.AgentModelResultMapper;
import com.ai.gateway.application.agent.AgentToolProjectionUseCase;
import com.ai.gateway.domain.model.AuditPlane;
import com.ai.gateway.domain.model.RequestContext;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 与传输层无关的 MCP 桥接器。
 *
 * <p>MCP SDK/传输适配器可调用本类；JSON-RPC 生命周期、会话建立与身份认证仍由该传输适配器负责。
 * 本类有意不暴露 Confirm、Cancel、Status。</p>
 *
 * <p>工具面由 {@link McpProjectedToolCatalog} 决定：默认仍是两个固定 Meta-Tool；开启直投后
 * 额外按会话身份投影已授权的只读能力（工具名为 {@code cap_<hash>} alias）。无论走哪条路径，
 * 执行都收敛到同一个 {@link AgentHostConnector#call} 调用与同一次执行期鉴权——
 * 直投只是在前面多了一步「alias → toolRef」的换算。</p>
 *
 * @author cmiracle@163.com
 * @since 0.1.0
 */
public final class McpGatewayAdapter {

    /** 未协商出语言偏好时的默认语言标签。 */
    private static final String DEFAULT_LOCALE = "zh-CN";

    /** BCP 47 语言标签的保守子集：只接受字母/数字子标签，拒绝 {@code *} 等通配形态。 */
    private static final java.util.regex.Pattern LANGUAGE_TAG =
            java.util.regex.Pattern.compile("[A-Za-z]{1,8}(-[A-Za-z0-9]{1,8}){0,4}");

    private final AgentHostConnector connector;
    private final McpSecurityMode securityMode;
    private final McpClientTrustRegistry trustRegistry;
    private final McpRateLimiter rateLimiter;
    private final McpProjectedToolCatalog toolCatalog;

    /**
     * 构造一个新的 McpGatewayAdapter。
     *
     * @param connector 与 Agent 应用交互的连接器，不能为 {@code null}
     */
    public McpGatewayAdapter(AgentHostConnector connector) {
        this(connector, McpSecurityMode.READ_ONLY,
                McpClientTrustRegistry.disabled(), McpRateLimiter.allowAll());
    }

    public McpGatewayAdapter(AgentHostConnector connector, McpSecurityMode securityMode) {
        this(connector, securityMode, McpClientTrustRegistry.disabled(),
                McpRateLimiter.allowAll());
    }

    public McpGatewayAdapter(AgentHostConnector connector,
                             McpSecurityMode securityMode,
                             McpClientTrustRegistry trustRegistry,
                             McpRateLimiter rateLimiter) {
        this(connector, securityMode, trustRegistry, rateLimiter,
                McpProjectedToolCatalog.metaToolOnly());
    }

    /**
     * @param toolCatalog 工具面策略；{@link McpProjectedToolCatalog#metaToolOnly()} 等价于
     *                    改造前的固定两工具行为
     */
    public McpGatewayAdapter(AgentHostConnector connector,
                             McpSecurityMode securityMode,
                             McpClientTrustRegistry trustRegistry,
                             McpRateLimiter rateLimiter,
                             McpProjectedToolCatalog toolCatalog) {
        this.connector = Objects.requireNonNull(connector);
        this.securityMode = Objects.requireNonNull(securityMode);
        this.trustRegistry = Objects.requireNonNull(trustRegistry);
        this.rateLimiter = Objects.requireNonNull(rateLimiter);
        this.toolCatalog = Objects.requireNonNull(toolCatalog);
        if (securityMode == McpSecurityMode.DISABLED) {
            throw new IllegalArgumentException("MCP adapter must not be constructed in DISABLED mode");
        }
    }

    /**
     * 返回与身份无关的固定 Meta-Tool 列表。
     *
     * <p>供在会话建立前就需要一份静态工具面的传输层使用（MCP SDK 的高层服务在启动时
     * 静态注册工具）。直投清单必须用 {@link #toolsList(RequestContext)} 获取。</p>
     *
     * @return 仅包含 {@code gateway_resolve} 与 {@code gateway_call} 的工具清单
     */
    public List<McpMetaToolCatalog.McpTool> toolsList() {
        return McpMetaToolCatalog.tools();
    }

    /**
     * 返回该请求身份可见的 {@code tools/list} 内容。
     *
     * <p>同一网关对不同身份返回不同工具集：未被授权的能力不会出现，其名称与存在性都不
     * 泄漏。这不是新增泄漏面——{@code GET /api/v1/tools} 早已向同一身份提供同样的信息。</p>
     *
     * @param context 请求上下文，不能为 {@code null}
     * @return 该身份可见的工具清单
     */
    public List<McpMetaToolCatalog.McpTool> toolsList(RequestContext context) {
        return toolCatalog.tools(context);
    }

    /** @return 当前工具曝光模式 */
    public McpToolExposureMode exposureMode() {
        return toolCatalog.mode();
    }

    /**
     * 按工具名分发调用。
     *
     * <p>两个固定 Meta-Tool 之外，若已开启直投，未知工具名会被当作 alias 尝试反查：
     * 反查成功即现场签发 {@code toolRef} 并复用 {@code gateway_call} 的执行路径；
     * 反查失败（不存在、已撤权、越权）统一返回 {@code CAPABILITY_UNAVAILABLE}。</p>
     *
     * @param toolName   工具名
     * @param context    请求上下文
     * @param requestId  请求标识
     * @param arguments  工具参数
     * @return MCP 调用结果
     */
    public McpResult invoke(String toolName,
                            RequestContext context,
                            String requestId,
                            Map<String, Object> arguments) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(arguments, "arguments must not be null");
        if (toolName == null || toolName.isBlank()
                || requestId == null || requestId.isBlank()) {
            return McpResult.error("INVALID_ARGUMENTS", "tool name is required");
        }
        return switch (toolName) {
            case "gateway_resolve" -> resolve(context, requestId, arguments);
            case "gateway_call" -> call(context, requestId, arguments);
            default -> projected(toolName, context, requestId, arguments);
        };
    }

    /**
     * 处理直投工具调用：先把 alias 换成执行凭据，再走与 {@code gateway_call} 完全相同的执行路径。
     *
     * <p>严格按设计 §4.4 的顺序执行——认证与 alias 反查、重新授权、签发 {@code toolRef}
     * 由 {@link McpProjectedToolCatalog#bind} 完成，本方法只负责第 5 步的参数拼装。
     * 轮次标识按 {@code requestId} 派生且每次调用独立：{@code AgentTurnStore} 会把首个被
     * 取用的 {@code toolRef} 钉死在轮次上，共用轮次会让第二次调用直接失败。</p>
     *
     * <p><b>入参形态即 {@code tools/list} 公布的能力 Schema 本身</b>，没有额外信封。
     * 直投工具的契约就是那份 Schema：若这里再要求 {@code {"arguments": {...}}} 这类包装，
     * 任何遵循 Schema 的标准 MCP 客户端都会调用失败。因此整张入参映射被原样当作能力入参，
     * 也就不存在「保留字」——某个能力恰好有名为 {@code toolRef} 或 {@code locale} 的字段时
     * 不会被误读为协议字段。入参的合法性由执行期的确定性 Schema 校验判定，可信字段
     * （{@code orgId} 等）由 {@code ArgumentSource.PRINCIPAL} 注入覆盖，与 {@code gateway_call}
     * 的信任姿态完全一致。</p>
     */
    private McpResult projected(String alias, RequestContext context, String requestId,
                                Map<String, Object> arguments) {
        if (!toolCatalog.supportsAliasDispatch()) {
            return McpResult.error("UNKNOWN_TOOL", "Unknown MCP tool");
        }
        if (!rateLimiter.tryAcquire(McpRateLimiter.CALL)) {
            return McpResult.error("MCP_RATE_LIMITED", "MCP call rate limit reached");
        }
        String agentTurnId = "proj:" + requestId;
        AgentToolProjectionUseCase.BindResult bound =
                toolCatalog.bind(context, alias, agentTurnId, requestId);
        if (bound == null || bound.status() != AgentToolProjectionUseCase.Status.COMPLETED) {
            // 未知 alias、已撤权与越权收敛为同一响应：任何差异都会把「该身份未被授权的
            // 能力是否存在」变成可探测信息。
            return McpResult.error(bound == null ? "CAPABILITY_UNAVAILABLE" : bound.errorCode(),
                    "Capability is not available");
        }
        // 直投能力必然是 READ_ONLY，故策略恒为 READ_ONLY，不参与受信客户端的写前置确认；
        // 平面仍固定为 MCP，成本与故障率与 Host 直连分开统计。
        // 幂等键取 requestId：它由「会话 + 工具名 + 入参」确定性派生，重试天然落到同一次操作。
        AgentHostConnector.CallResult hostResult = connector.call(
                context, scopedTurnId(context, bound.agentTurnId()), requestId,
                bound.toolRef(), arguments, negotiatedLocale(context), requestId,
                AgentHostConnector.CallPolicy.READ_ONLY, AuditPlane.MCP);
        return callResult(hostResult, requestId, bound.agentTurnId());
    }

    /**
     * 从 {@code Accept-Language} 协商语言标签。
     *
     * <p>MCP 的 {@code tools/call} 只有 {@code name} 与 {@code arguments} 两个字段，
     * 因此直投路径的语言偏好只能来自 HTTP 层；用标准请求头而不是自造协议字段，
     * 也避免与能力自身的 {@code locale} 入参撞名。取首个标签，形态不合法即回退默认值——
     * 语言偏好是展示偏好，不值得为它拒绝一次调用。</p>
     */
    private static String negotiatedLocale(RequestContext context) {
        String header = context.header("Accept-Language");
        if (header == null || header.isBlank()) {
            return DEFAULT_LOCALE;
        }
        String first = header.split(",", 2)[0].split(";", 2)[0].trim();
        return first.length() <= 32 && LANGUAGE_TAG.matcher(first).matches()
                ? first : DEFAULT_LOCALE;
    }


    /**
     * 处理 {@code gateway_resolve}：根据查询文本发现一小批已授权的能力候选。
     */
    private McpResult resolve(RequestContext context, String requestId,
                              Map<String, Object> arguments) {
        Object query = arguments.get("query");
        if (!(query instanceof String queryText) || queryText.isBlank()) {
            return McpResult.error("INVALID_ARGUMENTS", "query is required");
        }
        String invalid = validateKeys(arguments, "query", "agentTurnId", "topK", "locale");
        if (invalid != null || queryText.length() > 4096) {
            return McpResult.error("INVALID_ARGUMENTS", invalid == null
                    ? "query is too long" : invalid);
        }
        if (arguments.containsKey("topK") && !(arguments.get("topK") instanceof Number)) {
            return McpResult.error("INVALID_ARGUMENTS", "topK must be an integer");
        }
        int topK = number(arguments.get("topK"), 5);
        if (topK < 1 || topK > 5) {
            return McpResult.error("INVALID_ARGUMENTS", "topK must be between 1 and 5");
        }
        String invalidText = validateOptionalText(arguments, "agentTurnId");
        if (invalidText == null) {
            invalidText = validateOptionalText(arguments, "locale");
        }
        if (invalidText != null) {
            return McpResult.error("INVALID_ARGUMENTS", invalidText);
        }
        if (!rateLimiter.tryAcquire(McpRateLimiter.RESOLVE)) {
            return McpResult.error("MCP_RATE_LIMITED", "MCP resolve rate limit reached");
        }
        String agentTurnId = text(arguments.get("agentTurnId"), requestId);
        String locale = text(arguments.get("locale"), negotiatedLocale(context));
        if (agentTurnId.length() > 128 || locale.length() > 32) {
            return McpResult.error("INVALID_ARGUMENTS", "argument length exceeds limit");
        }
        AgentHostConnector.ResolveResult hostResult = connector.resolve(
                context, scopedTurnId(context, agentTurnId), requestId, queryText, topK);
        var result = hostResult.resolution();
        return McpResult.of(result.status().name(), Map.of(
                "requestId", requestId,
                "agentTurnId", agentTurnId,
                "catalogVersion", result.catalogVersion(),
                "policyEpoch", result.policyEpoch(),
                "candidates", result.candidates(),
                "selectedSchema", result.selectedSchema() == null
                        ? Map.of() : result.selectedSchema(),
                "locale", locale,
                "errorCode", result.errorCode() == null ? "" : result.errorCode()));
    }

    /**
     * 处理 {@code gateway_call}：调用当前网关轮次返回的某个能力。
     */
    private McpResult call(RequestContext context, String requestId,
                           Map<String, Object> arguments) {
        if (!(arguments.get("toolRef") instanceof String toolRef)
                || toolRef.isBlank()
                || !(arguments.get("arguments") instanceof Map<?, ?> rawArguments)) {
            return McpResult.error("INVALID_ARGUMENTS", "toolRef and arguments are required");
        }
        String invalid = validateKeys(arguments, "toolRef", "agentTurnId", "arguments",
                "locale", "idempotencyKey");
        if (invalid != null || toolRef.length() > 2048) {
            return McpResult.error("INVALID_ARGUMENTS", invalid == null
                    ? "toolRef is too long" : invalid);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> modelArguments = (Map<String, Object>) rawArguments;
        String invalidText = validateOptionalText(arguments, "agentTurnId");
        if (invalidText == null) {
            invalidText = validateOptionalText(arguments, "locale");
        }
        if (invalidText == null) {
            invalidText = validateOptionalText(arguments, "idempotencyKey");
        }
        if (invalidText != null) {
            return McpResult.error("INVALID_ARGUMENTS", invalidText);
        }
        if (!rateLimiter.tryAcquire(McpRateLimiter.CALL)) {
            return McpResult.error("MCP_RATE_LIMITED", "MCP call rate limit reached");
        }
        String locale = text(arguments.get("locale"), negotiatedLocale(context));
        if (locale.length() > 32) {
            return McpResult.error("INVALID_ARGUMENTS", "locale is too long");
        }
        String idempotencyKey = text(arguments.get("idempotencyKey"), requestId);
        if (idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            return McpResult.error("INVALID_ARGUMENTS", "idempotencyKey length is invalid");
        }
        String agentTurnId = text(arguments.get("agentTurnId"), requestId);
        if (agentTurnId.length() > 128) {
            return McpResult.error("INVALID_ARGUMENTS", "agentTurnId is too long");
        }
        boolean trustedClient = securityMode.allowWritePrepare()
                && trustRegistry.isTrusted(context);
        // 平面固定为 MCP：即使客户端受信、走 HOST_CONFIRMATION 策略，
        // 其成本与故障率也必须与 Host 直连分开统计。
        AgentHostConnector.CallResult hostResult = connector.call(
                context, scopedTurnId(context, agentTurnId), requestId, toolRef,
                modelArguments, locale, idempotencyKey,
                trustedClient
                        ? AgentHostConnector.CallPolicy.HOST_CONFIRMATION
                        : AgentHostConnector.CallPolicy.READ_ONLY,
                AuditPlane.MCP);
        return callResult(hostResult, requestId, agentTurnId);
    }

    /** Meta-Tool 与直投两条路径共用的结果投影，确保对模型可见的字段完全一致。 */
    private static McpResult callResult(AgentHostConnector.CallResult hostResult,
                                        String requestId, String agentTurnId) {
        var safe = hostResult.result();
        return McpResult.of(safe.status().name(), Map.of(
                "requestId", requestId,
                "agentTurnId", agentTurnId,
                "data", safe.data() == null ? Map.of() : safe.data(),
                "errorCode", safe.errorCode() == null ? "" : safe.errorCode(),
                "message", safeMessage(safe),
                "operationId", safe.operationId() == null ? "" : safe.operationId(),
                "expiresAt", safe.expiresAt() == null ? "" : safe.expiresAt()));
    }

    /**
     * 返回数值参数，缺省值只用于未提供参数；非法值由调用方拒绝。
     */
    private static int number(Object value, int defaultValue) {
        if (!(value instanceof Number number)) {
            return defaultValue;
        }
        return number.intValue();
    }

    /**
     * 返回非空文本参数，否则回退到默认值。
     */
    private static String text(Object value, String fallback) {
        return value instanceof String text && !text.isBlank() ? text : fallback;
    }

    private static String validateOptionalText(Map<String, Object> arguments, String key) {
        if (!arguments.containsKey(key)) {
            return null;
        }
        Object value = arguments.get(key);
        if (!(value instanceof String)) {
            return key + " must be a string";
        }
        if (((String) value).isBlank()) {
            return key + " must not be blank";
        }
        return null;
    }

    private static String validateKeys(Map<String, Object> arguments, String... allowed) {
        java.util.Set<String> allowedSet = java.util.Set.of(allowed);
        for (String key : arguments.keySet()) {
            if (!allowedSet.contains(key)) {
                return "unknown argument: " + key;
            }
        }
        return null;
    }

    private static String safeMessage(AgentModelResultMapper.ModelResult result) {
        return switch (result.status()) {
            case COMPLETED -> "Completed";
            case CONFIRMATION_REQUIRED -> "User confirmation required";
            case ERROR -> "Request failed";
        };
    }

    /**
     * 将请求中的 sessionId 与 turnId 组合为作用域化的轮次标识。
     *
     * <p>优先取 {@code Mcp-Session-Id} 请求头，其次兼容查询参数
     * {@code sessionId}；二者皆缺省时以 {@code direct} 标识直连调用。</p>
     */
    private static String scopedTurnId(RequestContext context, String turnId) {
        String sessionId = context.header("Mcp-Session-Id");
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = context.queryParams().get("sessionId");
        }
        return (sessionId == null || sessionId.isBlank() ? "direct" : sessionId)
                + ":" + turnId;
    }

    /**
     * MCP 调用结果封装：状态与内容负载的不可变记录。
     *
     * @param status  结果状态（如 {@code SUCCESS}、{@code ERROR}）
     * @param content 结果内容负载
     */
    public record McpResult(String status, Map<String, Object> content) {
        static McpResult of(String status, Map<String, Object> content) {
            return new McpResult(status, Map.copyOf(content));
        }

        static McpResult error(String code, String message) {
            return new McpResult("ERROR", Map.of("errorCode", code, "message", message));
        }
    }
}
