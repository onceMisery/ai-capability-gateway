package com.ai.gateway.example.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 演示如何与 AI 能力网关 API 交互的示例客户端。
 *
 * <p>该客户端覆盖以下能力：
 * <ul>
 * <li>自然语言查询</li>
 * <li>澄清会话续接</li>
 * <li>写操作的 prepare/confirm</li>
 * <li>管理面操作：导入、校验、审批、发布</li>
 * </ul>
 *
 * <p>使用示例：
 * <pre>{@code
 * GatewayApiClient client = new GatewayApiClient("http://localhost:8080", "my-jwt-token");
 *
 * // 自然语言查询
 * var result = client.naturalLanguageQuery("查询订单 SO202607210001", "zh-CN");
 * System.out.println(result);
 *
 * // 管理面：导入清单
 * client.importManifest(manifestYaml);
 * }</pre>
 *
 * <p>该客户端仅使用 JDK {@link HttpClient} 与 Jackson 处理 JSON，无需 Spring
 * 或其他框架依赖。</p>
 *
 * @author cmiracle@163.com
 * @since 0.1.0
 */
@Slf4j
public class GatewayApiClient {

    private static final String CONTENT_TYPE_JSON = "application/json";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private final String baseUrl;
    private final String authToken;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    /**
     * 构造一个新的 GatewayApiClient。
     *
     * @param baseUrl 网关基础 URL（如 "http://localhost:8080"）
     * @param authToken 用于鉴权的 JWT 或 SSO Bearer Token
     * @throws NullPointerException 当 baseUrl 或 authToken 为 null 时
     */
    public GatewayApiClient(String baseUrl, String authToken) {
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl must not be null");
        this.authToken = Objects.requireNonNull(authToken, "authToken must not be null");
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(DEFAULT_TIMEOUT)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    // ========================================================================
    // — Natural Language Query API
    // ========================================================================

    /**
     * 向网关发送自然语言查询。
     *
     * <p>接口：{@code POST /api/v1/natural-language/queries}</p>
     *
     * <p>响应状态字段指示结果：</p>
     * <ul>
     * <li>{@code COMPLETED} — 查询执行成功，数据位于 "data" 字段。</li>
     * <li>{@code CLARIFICATION_REQUIRED} — 需要补充输入，使用返回的 interactionId 调用
     * {@link #continueClarification(String, String)}。</li>
     * <li>{@code NO_MATCH} — 无能力匹配该查询。</li>
     * <li>{@code ERROR} — 发生错误，查看 "errorCode" 与 "message"。</li>
     * </ul>
     *
     * @param text 自然语言查询文本（如 "查询订单 SO202607210001"）
     * @param locale 请求语言区域（如 "zh-CN"）
     * @return 解析后的 JSON 响应（Map 形式）
     * @throws GatewayApiException 当请求失败或响应无法解析时
     */
    public Map<String, Object> naturalLanguageQuery(String text, String locale) {
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("requestId", java.util.UUID.randomUUID().toString());
        requestBody.put("text", text);
        requestBody.put("locale", locale);
        requestBody.put("timezone", "Asia/Shanghai");

        return post("/api/v1/natural-language/queries", requestBody);
    }

    /**
     * 使用用户补充输入续接澄清会话。
     *
     * <p>接口：{@code POST /api/v1/natural-language/interactions/{interactionId}/messages}</p>
     *
     * <p>当查询返回 {@code CLARIFICATION_REQUIRED} 时，响应会包含
     * {@code interactionId}。使用本方法提供缺失信息。</p>
     *
     * <p>重要约束：</p>
     * <ul>
     * <li>后续回答只能补充缺失信息，或在原候选集内消歧。</li>
     * <li>若用户回复触发 NO_MATCH，或选择了原候选集之外的别名，
     * 该 interactionId 将失效，需重新启动完整路由流程。</li>
     * <li>Principal 变更、会话过期、能力下线或策略变更也会强制重新开始。</li>
     * </ul>
     *
     * @param interactionId 来自之前响应的澄清交互 ID
     * @param text 用户的补充输入文本
     * @return 解析后的 JSON 响应（Map 形式）
     * @throws GatewayApiException 当请求失败或响应无法解析时
     */
    public Map<String, Object> continueClarification(String interactionId, String text) {
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("text", text);

        return post("/api/v1/natural-language/interactions/" + interactionId + "/messages",
                requestBody);
    }

    // ========================================================================
    // — Write Operation Prepare/Confirm API
    // ========================================================================

    /**
     * 准备一次写操作以供确认。
     *
     * <p>接口：{@code POST /api/v1/natural-language/actions:prepare}</p>
     *
     * <p>Prepare 阶段执行：参数绑定、鉴权检查，并持久化一条不可变操作记录。
     * 同时为 Confirm 阶段签发一个短时效的确认令牌。</p>
     *
     * <p>响应包含：</p>
     * <ul>
     * <li>{@code operationId} — 唯一的操作标识。</li>
     * <li>{@code confirmationToken} — Confirm 阶段所需的令牌。</li>
     * <li>{@code summary} — 人类可读的操作摘要。</li>
     * <li>{@code expiresAt} — 确认令牌的过期时间。</li>
     * </ul>
     *
     * @param text 自然语言写请求（如 "取消订单 SO202607210001"）
     * @return 解析后的 JSON 响应（Map 形式）
     * @throws GatewayApiException 当请求失败或响应无法解析时
     */
    public Map<String, Object> prepareAction(String text) {
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("text", text);
        requestBody.put("locale", "zh-CN");
        requestBody.put("timezone", "Asia/Shanghai");

        return post("/api/v1/natural-language/actions:prepare", requestBody);
    }

    /**
     * 确认并执行一个已准备的写操作。
     *
     * <p>接口：{@code POST /api/v1/operations/{operationId}:confirm}</p>
     *
     * <p>Confirm 阶段使用确认令牌原子地认领执行权并调用 Provider。响应包含最终的操作
     * 状态（SUCCEEDED、FAILED、UNKNOWN 等）。</p>
     *
     * @param operationId 来自 Prepare 阶段的操作 ID
     * @param confirmToken 来自 Prepare 阶段的确认令牌
     * @return 解析后的 JSON 响应（Map 形式）
     * @throws GatewayApiException 当请求失败或响应无法解析时
     */
    public Map<String, Object> confirmOperation(String operationId, String confirmToken) {
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("token", confirmToken);

        return post("/api/v1/operations/" + operationId + ":confirm", requestBody);
    }

    /**
     * 查询写操作的当前状态。
     *
     * <p>接口：{@code GET /api/v1/operations/{operationId}}</p>
     *
     * <p>响应包含操作状态，遵循如下状态机：</p>
     * <pre>
     * PREPARED -> EXECUTING -> SUCCEEDED
     * | |----> FAILED
     * | +----> UNKNOWN -> SUCCEEDED / FAILED / MANUAL_REVIEW
     * |-----------------> EXPIRED
     * +-----------------> CANCELLED
     * </pre>
     *
     * @param operationId 操作标识
     * @return 解析后的 JSON 响应（Map 形式）
     * @throws GatewayApiException 当请求失败或响应无法解析时
     */
    public Map<String, Object> getOperationStatus(String operationId) {
        return get("/api/v1/operations/" + operationId);
    }

    // ========================================================================
    // — Admin / Control Plane API
    // ========================================================================

    /**
     * 通过 10 步校验流水线导入能力清单（Capability Manifest）。
     *
     * <p>接口：{@code POST /admin/v1/manifests:import}</p>
     *
     * <p>清单将依据带版本的 JSON Schema 与 10 步流水线进行校验，包括：Schema 校验、
     * ID/版本格式检查、入参 Schema 安全约束、参数绑定一致性、序列化白名单、
     * 出参契约校验等。</p>
     *
     * @param manifestYaml YAML 格式的能力清单
     * @return 解析后的 JSON 响应（Map 形式），含 status 与 validationReport
     * @throws GatewayApiException 当请求失败或响应无法解析时
     */
    public Map<String, Object> importManifest(String manifestYaml) {
        // 管理面导入接口以 JSON 体接收清单。真实场景下 YAML 会先转为 JSON，
        // 或接口直接接受 YAML content-type。此处直接以 YAML content-type 发送原始 YAML 字符串。
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/admin/v1/manifests:import"))
                    .header("Authorization", "Bearer " + authToken)
                    .header("Content-Type", "application/x-yaml")
                    .timeout(DEFAULT_TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofString(manifestYaml))
                    .build();

            log.info("Importing manifest to gateway...");
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            log.debug("Import response: status={}, body={}", response.statusCode(),
                    truncate(response.body(), 500));

            return parseResponse(response);
        } catch (GatewayApiException e) {
            throw e;
        } catch (Exception e) {
            throw new GatewayApiException("Failed to import manifest: " + e.getMessage(), e);
        }
    }

    /**
     * 重新校验一个已存在的清单版本。
     *
     * <p>接口：{@code POST /admin/v1/capabilities/{id}/versions/{version}:validate}</p>
     *
     * @param id 能力标识（如 "order.detail.query"）
     * @param version 语义化版本（如 "1.0.0"）
     * @return 解析后的 JSON 响应（Map 形式），含校验状态
     * @throws GatewayApiException 当请求失败或响应无法解析时
     */
    public Map<String, Object> validateCapability(String id, String version) {
        return post("/admin/v1/capabilities/" + id + "/versions/" + version + ":validate",
                Map.of());
    }

    /**
     * 审批一个已校验的清单。
     *
     * <p>接口：{@code POST /admin/v1/capabilities/{id}/versions/{version}:approve}</p>
     *
     * <p>审批将清单从 VALIDATED 状态迁移至 APPROVED，使其具备发布资格。</p>
     *
     * @param id 能力标识
     * @param version 语义化版本
     * @return 解析后的 JSON 响应（Map 形式），含审批状态
     * @throws GatewayApiException 当请求失败或响应无法解析时
     */
    public Map<String, Object> approveCapability(String id, String version) {
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("approver", "admin@example.com");

        return post("/admin/v1/capabilities/" + id + "/versions/" + version + ":approve",
                requestBody);
    }

    /**
     * 向指定环境发布新的目录快照。
     *
     * <p>接口：{@code POST /admin/v1/releases:publish}</p>
     *
     * <p>发布会生成一个包含全部 APPROVED 能力的不可变快照。快照版本单调递增，
     * 创建后其内容不可再修改。</p>
     *
     * @param environment 目标环境（如 "production"、"staging"）
     * @return 解析后的 JSON 响应（Map 形式），含新的 snapshotVersion
     * @throws GatewayApiException 当请求失败或响应无法解析时
     */
    public Map<String, Object> publishRelease(String environment) {
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("environment", environment);

        return post("/admin/v1/releases:publish", requestBody);
    }

    /**
     * 立即下线一个能力。
     *
     * <p>接口：{@code POST /admin/v1/capabilities/{id}:suspend}</p>
     *
     * <p>下线是一项应急操作，会立即从活动目录快照中移除该能力，并生成一份不包含
     * 该能力的新快照版本。</p>
     *
     * @param id 待下线能力的标识
     * @param reason 下线原因（用于审计追溯）
     * @return 解析后的 JSON 响应（Map 形式），含下线状态
     * @throws GatewayApiException 当请求失败或响应无法解析时
     */
    public Map<String, Object> suspendCapability(String id, String reason) {
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("reason", reason);
        requestBody.put("operator", "admin@example.com");

        return post("/admin/v1/capabilities/" + id + ":suspend", requestBody);
    }

    // ========================================================================
    // — Health Check API
    // ========================================================================

    /**
     * 检查网关健康状态。
     *
     * <p>接口：{@code GET /health/readiness}</p>
     *
     * <p>就绪探针检查：数据库连通性、活动快照已加载、所需密钥可用、适配器已初始化。</p>
     *
     * @return 解析后的 JSON 响应（Map 形式），含健康检查结果
     * @throws GatewayApiException 当请求失败或响应无法解析时
     */
    public Map<String, Object> getHealth() {
        return get("/health/readiness");
    }

    // ========================================================================
    // Internal HTTP helpers
    // ========================================================================

    /**
     * 发送带 JSON 体的 POST 请求并返回解析后的响应。
     *
     * @param path API 路径（拼接在 baseUrl 之后）
     * @param requestBody 需序列化为 JSON 的请求体
     * @return 解析后的 JSON 响应（Map 形式）
     * @throws GatewayApiException 当请求失败或响应无法解析时
     */
    private Map<String, Object> post(String path, Map<String, Object> requestBody) {
        try {
            String jsonBody = objectMapper.writeValueAsString(requestBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    .header("Authorization", "Bearer " + authToken)
                    .header("Content-Type", CONTENT_TYPE_JSON)
                    .timeout(DEFAULT_TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            log.info("POST {} ", path);
            log.debug("Request body: {}", truncate(jsonBody, 500));

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            log.debug("Response: status={}, body={}", response.statusCode(),
                    truncate(response.body(), 500));

            return parseResponse(response);
        } catch (GatewayApiException e) {
            throw e;
        } catch (Exception e) {
            throw new GatewayApiException(
                    "POST " + path + " failed: " + e.getMessage(), e);
        }
    }

    /**
     * 发送 GET 请求并返回解析后的响应。
     *
     * @param path API 路径（拼接在 baseUrl 之后）
     * @return 解析后的 JSON 响应（Map 形式）
     * @throws GatewayApiException 当请求失败或响应无法解析时
     */
    private Map<String, Object> get(String path) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    .header("Authorization", "Bearer " + authToken)
                    .header("Accept", CONTENT_TYPE_JSON)
                    .timeout(DEFAULT_TIMEOUT)
                    .GET()
                    .build();

            log.info("GET {}", path);

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            log.debug("Response: status={}, body={}", response.statusCode(),
                    truncate(response.body(), 500));

            return parseResponse(response);
        } catch (GatewayApiException e) {
            throw e;
        } catch (Exception e) {
            throw new GatewayApiException(
                    "GET " + path + " failed: " + e.getMessage(), e);
        }
    }

    /**
     * 将 HTTP 响应体解析为 JSON Map。
     *
     * <p>若响应体为空或不是合法 JSON，则返回一份描述性的错误 Map 而非抛异常，
     * 以便调用方查看 HTTP 状态码。</p>
     *
     * @param response HTTP 响应
     * @return 解析后的响应体（Map 形式）
     * @throws GatewayApiException 当响应体无法解析时
     */
    private Map<String, Object> parseResponse(HttpResponse<String> response) {
        String body = response.body();
        if (body == null || body.isBlank()) {
            Map<String, Object> errorResult = new LinkedHashMap<>();
            errorResult.put("httpStatus", response.statusCode());
            errorResult.put("message", "Empty response body");
            return errorResult;
        }

        try {
            Map<String, Object> result = objectMapper.readValue(body,
                    new TypeReference<LinkedHashMap<String, Object>>() {});
            result.put("httpStatus", response.statusCode());
            return result;
        } catch (Exception e) {
            throw new GatewayApiException(
                    "Failed to parse response as JSON (HTTP " + response.statusCode()
                            + "): " + truncate(body, 200), e);
        }
    }

    /**
     * 将字符串截断到指定最大长度，用于日志输出。
     *
     * @param value 待截断的字符串
     * @param maxLength 最大长度
     * @return 截断后的字符串，若被截断则末尾追加 "..." 后缀
     */
    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return "null";
        }
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }

    /**
     * 网关 API 调用失败时抛出的异常。
     *
     * <p>该类将传输错误、JSON 解析错误及其他失败以描述性消息进行包装。</p>
     *
     * @author cmiracle@163.com
     */
    public static class GatewayApiException extends RuntimeException {

        /**
         * 构造一个新的 GatewayApiException。
         *
         * @param message 错误消息
         * @param cause 底层原因
         */
        public GatewayApiException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
