package com.ai.gateway.adapter.llm;

import com.ai.gateway.domain.model.ErrorCode;
import com.ai.gateway.domain.model.ModelDecision;
import com.ai.gateway.domain.port.LlmRouterPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * 基于 HTTP 的 {@link LlmRouterPort} 实现，使用 JDK HttpClient。
 *
 * <p>该适配器通过 {@link java.net.http.HttpClient} 调用 LLM API，发送受限的候选
 * 上下文，并解析模型的响应。它使用结构化输出或 Function Calling，并对最终 JSON
 * 执行本地 Schema 校验。</p>
 *
 * <p>关键安全约束：</p>
 * <ul>
 * <li>模型提供方的响应内容不会完整记录到日志。</li>
 * <li>如果 LLM 不可用，返回明确错误，或路由到人工录入——绝不能退化为猜测接口。</li>
 * <li>提示词模板、模型 ID、temperature 和解析器版本均经过版本化管理。</li>
 * </ul>
 *
 * @author cmiracle@163.com
 * @since 0.1.0
 */
@Component
public class HttpLlmRouterAdapter implements LlmRouterPort {

    private static final Logger log = LoggerFactory.getLogger(HttpLlmRouterAdapter.class);

    private static final String AUTH_HEADER = "Authorization";
    private static final String AUTH_PREFIX = "Bearer ";
    private static final String CONTENT_TYPE = "Content-Type";
    private static final String CONTENT_TYPE_JSON = "application/json";
    public static final int DEFAULT_MAX_RESPONSE_BYTES = 1024 * 1024;

    private final HttpClient httpClient;
    private final LlmRequestBuilder requestBuilder;
    private final LlmResponseParser responseParser;
    private final PromptTemplateRegistry templateRegistry;
    private final ObjectMapper objectMapper;

    private final String endpoint;
    private final String apiKey;
    private final String model;
    private final double temperature;
    private final int maxTokens;
    private final int maxResponseBytes;

    /**
     * 构造一个新的 HttpLlmRouterAdapter。
     *
     * @param endpoint LLM API 端点 URL
     * @param apiKey 用于认证的 API 密钥
     * @param model 模型标识符（已版本化）
     * @param temperature 采样温度（已版本化）
     * @param maxTokens 响应中的最大 token 数
     * @param requestBuilder 用于受限候选上下文的请求构建器
     * @param responseParser 用于模型决策的响应解析器
     * @param templateRegistry 提示词模板注册表
     */
    public HttpLlmRouterAdapter(String endpoint,
                                String apiKey,
                                String model,
                                double temperature,
                                int maxTokens,
                                LlmRequestBuilder requestBuilder,
                                LlmResponseParser responseParser,
                                PromptTemplateRegistry templateRegistry) {
        this(endpoint, apiKey, model, temperature, maxTokens, requestBuilder,
                responseParser, templateRegistry, DEFAULT_MAX_RESPONSE_BYTES);
    }

    public HttpLlmRouterAdapter(String endpoint,
                                String apiKey,
                                String model,
                                double temperature,
                                int maxTokens,
                                LlmRequestBuilder requestBuilder,
                                LlmResponseParser responseParser,
                                PromptTemplateRegistry templateRegistry,
                                int maxResponseBytes) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint must not be null");
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey must not be null");
        this.model = Objects.requireNonNull(model, "model must not be null");
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        if (maxResponseBytes <= 0) {
            throw new IllegalArgumentException("maxResponseBytes must be positive");
        }
        this.maxResponseBytes = maxResponseBytes;
        this.requestBuilder = Objects.requireNonNull(requestBuilder,
                "requestBuilder must not be null");
        this.responseParser = Objects.requireNonNull(responseParser,
                "responseParser must not be null");
        this.templateRegistry = Objects.requireNonNull(templateRegistry,
                "templateRegistry must not be null");
        this.objectMapper = new ObjectMapper();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        log.info("HttpLlmRouterAdapter initialized: endpoint={}, model={}, temperature={}, maxTokens={}",
                endpoint, model, temperature, maxTokens);
    }

    @Override
    public ModelDecision route(String userText, List<LlmCandidate> candidates) {
        Objects.requireNonNull(userText, "userText must not be null");
        Objects.requireNonNull(candidates, "candidates must not be null");

        if (candidates.isEmpty()) {
            log.warn("No candidates provided for routing, returning NO_MATCH");
            return new ModelDecision.NoMatchDecision("NO_CANDIDATES_PROVIDED");
        }

        try {
            // 步骤 1：构建受限候选上下文
            String candidateContext = requestBuilder.buildRequest(userText, candidates);

            // 步骤 2：构建 LLM API 请求体
            String requestBody = buildApiRequestBody(candidateContext);

            // 步骤 3：向 LLM 端点发送 HTTP POST 请求
            String responseBody = sendRequest(requestBody);

            // 步骤 4：从 API 响应中提取模型内容
            String modelContent = extractModelContent(responseBody);

            // 步骤 5：本地 Schema 校验并解析
            ModelDecision decision = responseParser.parse(modelContent);

            // 步骤 6：针对候选集校验决策
            // 网关在模型返回后执行确定性检查。
            // 在此处先行对候选集进行别名校验作为第一道关卡。
            validateDecisionAgainstCandidates(decision, candidates);

            log.debug("LLM routing completed: decision type={}",
                    decision.getClass().getSimpleName());
            return decision;

        } catch (LlmRouterPort.LlmRoutingException e) {
            log.error("LLM unavailable: {}", e.getMessage());
            throw e;
        } catch (RuntimeException e) {
            log.error("LLM routing failed: {}", e.getMessage());
            throw e;
        }
    }

    /**
     * 以 chat completions 格式构建 LLM API 请求体。
     *
     * <p>系统提示词将 LLM 约束为只能从提供的候选中选择。用户消息包含来自
     * {@link LlmRequestBuilder} 的受限候选上下文。</p>
     *
     * @param candidateContext 受限候选上下文的 JSON 字符串
     * @return API 请求体的 JSON 字符串
     */
    private String buildApiRequestBody(String candidateContext) {
        try {
            ObjectNode rootNode = objectMapper.createObjectNode();
            rootNode.put("model", model);
            rootNode.put("temperature", temperature);
            rootNode.put("max_tokens", maxTokens);

            // 消息：系统提示词 + 包含候选上下文的用户消息
            ArrayNode messagesArray = rootNode.putArray("messages");

            // 从注册表获取系统提示词（已版本化）
            String systemPrompt = templateRegistry.getTemplate("default-system");
            if (systemPrompt != null) {
                ObjectNode systemMessage = messagesArray.addObject();
                systemMessage.put("role", "system");
                systemMessage.put("content", systemPrompt);
            }

            // 包含受限候选上下文的用户消息
            ObjectNode userMessage = messagesArray.addObject();
            userMessage.put("role", "user");
            userMessage.put("content", candidateContext);

            return objectMapper.writeValueAsString(rootNode);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build LLM API request body", e);
        }
    }

    /**
     * 向 LLM 端点发送 HTTP POST 请求。
     *
     * <p>模型提供方的响应内容不会完整记录到日志。仅记录 HTTP 状态码与结构化的
     * 元数据。</p>
     *
     * @param requestBody API 请求体的 JSON 字符串
     * @return 响应体字符串
     * @throws LlmRouterPort.LlmRoutingException 如果 LLM 不可达或返回服务端错误
     */
    private String sendRequest(String requestBody) {
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header(CONTENT_TYPE, CONTENT_TYPE_JSON)
                    .header(AUTH_HEADER, AUTH_PREFIX + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<InputStream> response = httpClient.send(
                    httpRequest, HttpResponse.BodyHandlers.ofInputStream());

            int statusCode = response.statusCode();
            if (statusCode >= 200 && statusCode < 300) {
                log.debug("LLM API responded with status: {}", statusCode);
                try (InputStream body = response.body()) {
                    return readBoundedBody(body, maxResponseBytes);
                }
            } else if (statusCode == 429) {
                log.warn("LLM API rate limit reached");
                throw new LlmRouterPort.LlmRoutingException(
                        ErrorCode.RATE_LIMITED, "LLM provider rate limit reached");
            } else {
                // LLM 不可用——不记录完整响应体
                log.error("LLM API returned non-success status: {} (response body not logged)", statusCode);
                throw new LlmRouterPort.LlmRoutingException(
                        ErrorCode.LLM_UNAVAILABLE, "LLM provider unavailable");
            }
        } catch (java.net.ConnectException e) {
            log.error("Failed to connect to LLM endpoint: {}", e.getMessage());
            throw new LlmRouterPort.LlmRoutingException(
                    ErrorCode.LLM_UNAVAILABLE, "LLM endpoint unreachable", e);
        } catch (java.net.http.HttpTimeoutException e) {
            log.error("LLM API request timed out");
            throw new LlmRouterPort.LlmRoutingException(
                    ErrorCode.LLM_UNAVAILABLE, "LLM request timed out", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmRouterPort.LlmRoutingException(
                    ErrorCode.LLM_UNAVAILABLE, "LLM request interrupted", e);
        } catch (LlmRouterPort.LlmRoutingException e) {
            throw e;
        } catch (Exception e) {
            log.error("LLM API request failed: {}", e.getMessage());
            throw new LlmRouterPort.LlmRoutingException(
                    ErrorCode.LLM_UNAVAILABLE, "LLM request failed", e);
        }
    }

    static String readBoundedBody(InputStream input, int maxBytes) {
        Objects.requireNonNull(input, "input must not be null");
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream(
                    Math.min(maxBytes, 8192));
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (output.size() + read > maxBytes) {
                    throw new LlmRouterPort.LlmRoutingException(
                            ErrorCode.INVALID_MODEL_OUTPUT,
                            "LLM response exceeds maximum of " + maxBytes + " bytes");
                }
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new LlmRouterPort.LlmRoutingException(
                    ErrorCode.LLM_UNAVAILABLE, "Failed to read LLM response", e);
        }
    }

    /**
     * 从 API 响应中提取模型的内容。
     *
     * <p>标准 chat completions 响应将模型输出放在
     * {@code choices[0].message.content} 中。如果响应本身已经是直接的决策 JSON
     * （未被 choices 包裹），则原样返回。</p>
     *
     * @param responseBody 原始 API 响应体
     * @return 模型的内容字符串（即决策 JSON）
     */
    private String extractModelContent(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);

            // 标准 chat completions 格式：choices[0].message.content
            JsonNode choicesNode = root.get("choices");
            if (choicesNode != null && choicesNode.isArray() && !choicesNode.isEmpty()) {
                JsonNode messageNode = choicesNode.get(0).get("message");
                if (messageNode != null) {
                    JsonNode contentNode = messageNode.get("content");
                    if (contentNode != null && !contentNode.isNull()) {
                        return contentNode.asText();
                    }
                }
            }

            // 兜底：响应体本身即为决策 JSON
            if (root.has("decision")) {
                return responseBody;
            }

            log.error("LLM response does not contain model content in expected location");
            throw new RuntimeException(
                    "Invalid LLM response: no model content found in choices[0].message.content or root");
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to extract model content from response", e);
        }
    }

    /**
     * 校验 SELECT 决策的别名是否属于所提供的候选集。
     *
     * <p>这是模型返回后执行的第一个确定性检查。网关会在后续流水线阶段执行额外的
     * 检查（授权、能力状态、Schema 校验）。</p>
     *
     * @param decision 模型的决策
     * @param candidates 已授权的候选集
     */
    private void validateDecisionAgainstCandidates(ModelDecision decision,
                                                   List<LlmCandidate> candidates) {
        if (decision instanceof ModelDecision.SelectDecision select) {
            String selectedAlias = select.alias();
            boolean aliasInCandidateSet = candidates.stream()
                    .anyMatch(c -> c.alias().equals(selectedAlias));
            if (!aliasInCandidateSet) {
                log.error("Model selected alias not in candidate set: {}", selectedAlias);
                throw new RuntimeException(
                        "Model selected alias not in candidate set: " + selectedAlias);
            }
        }
    }

    /**
     * 优雅地关闭 HTTP 客户端。
     */
    @PreDestroy
    public void shutdown() {
        log.info("Shutting down HttpLlmRouterAdapter...");
        // JDK 21 中的 HttpClient 自行管理其资源，且是可自动关闭的
        log.info("HttpLlmRouterAdapter shutdown complete");
    }

}
