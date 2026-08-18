package com.ai.gateway.adapter.llm;

import com.ai.gateway.domain.model.ModelDecision;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import lombok.extern.slf4j.Slf4j;

/**
 * 将 LLM 响应解析为 {@link ModelDecision}。
 *
 * <p>模型必须且只能返回三种决策类型之一。该解析器提取 decision 字段并构造对应的
 * {@link ModelDecision} 子类型：</p>
 * <ul>
 * <li>{@code SELECT}：提取别名与参数</li>
 * <li>{@code CLARIFY}：提取追问问题</li>
 * <li>{@code NO_MATCH}：提取原因码</li>
 * </ul>
 *
 * <p>模型提供方的响应内容不会完整记录到日志。仅以适当的级别记录结构化的元数据
 * （决策类型、别名、错误指示）。</p>
 *
 * @author cmiracle@163.com
 * @since 0.1.0
 */
@Slf4j
@Component
public class LlmResponseParser {

    private final ObjectMapper objectMapper;

    /**
     * 使用默认的 ObjectMapper 构造一个新的 LlmResponseParser。
     */
    public LlmResponseParser() {
        this(new ObjectMapper());
    }

    /**
     * 使用自定义的 ObjectMapper 构造一个新的 LlmResponseParser。
     *
     * @param objectMapper JSON 反序列化器
     */
    public LlmResponseParser(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper,
                "objectMapper must not be null");
    }

    /**
     * 将 LLM 响应体解析为 {@link ModelDecision}。
     *
     * <p>响应必须包含 {@code decision} 字段，其值为以下之一：
     * {@code SELECT}、{@code CLARIFY} 或 {@code NO_MATCH}。解析器根据决策类型
     * 提取对应的字段。</p>
     *
     * @param responseBody 以 JSON 字符串形式表示的原始 LLM 响应体
     * @return 解析出的模型决策；永不为 {@code null}
     * @throws RuntimeException 如果响应无法解析，或 decision 字段缺失/无效
     */
    @SuppressWarnings("unchecked")
    public ModelDecision parse(String responseBody) {
        Objects.requireNonNull(responseBody, "responseBody must not be null");

        JsonNode root;
        try {
            root = objectMapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(responseBody);
        } catch (Exception e) {
            log.error("Failed to parse LLM response as JSON: {}", e.getMessage());
            throw new RuntimeException("Invalid LLM response: not valid JSON", e);
        }

        if (root == null || !root.isObject()) {
            throw new RuntimeException("Invalid LLM response: root must be an object");
        }
        JsonNode decisionNode = root.get("decision");
        if (decisionNode == null || !decisionNode.isTextual()
                || decisionNode.asText().isBlank()) {
            log.error("LLM response missing required 'decision' field");
            throw new RuntimeException(
                    "Invalid LLM response: 'decision' must be a non-blank string");
        }

        String decision = decisionNode.asText();
        log.debug("Parsing LLM decision: {}", decision);

        return switch (decision) {
            case "SELECT" -> parseSelectDecision(root);
            case "CLARIFY" -> parseClarifyDecision(root);
            case "NO_MATCH" -> parseNoMatchDecision(root);
            default -> {
                log.error("Unknown LLM decision type: {}", decision);
                throw new RuntimeException(
                        "Invalid LLM response: unknown decision type: " + decision);
            }
        };
    }

    /**
     * 从响应根节点解析 SELECT 决策。
     *
     * @param root 响应根节点
     * @return {@link ModelDecision.SelectDecision}
     */
    private ModelDecision parseSelectDecision(JsonNode root) {
        requireOnlyFields(root, Set.of("decision", "alias", "arguments"));
        String alias = requireText(root, "alias");
        Map<String, Object> arguments;

        JsonNode argumentsNode = root.get("arguments");
        if (argumentsNode == null || !argumentsNode.isObject()) {
            throw new RuntimeException(
                    "Invalid LLM response: 'arguments' must be an object");
        }
        try {
            arguments = objectMapper.treeToValue(argumentsNode, Map.class);
        } catch (Exception e) {
            log.error("Failed to parse 'arguments' field in SELECT decision", e);
            throw new RuntimeException(
                    "Invalid LLM response: failed to parse 'arguments' field", e);
        }

        log.debug("Parsed SELECT decision: alias={}", alias);
        return new ModelDecision.SelectDecision(alias, arguments);
    }

    /**
     * 从响应根节点解析 CLARIFY 决策。
     *
     * @param root 响应根节点
     * @return {@link ModelDecision.ClarifyDecision}
     */
    private ModelDecision parseClarifyDecision(JsonNode root) {
        requireOnlyFields(root, Set.of("decision", "question"));
        String question = requireText(root, "question");
        log.debug("Parsed CLARIFY decision");
        return new ModelDecision.ClarifyDecision(question);
    }

    /**
     * 从响应根节点解析 NO_MATCH 决策。
     *
     * @param root 响应根节点
     * @return {@link ModelDecision.NoMatchDecision}
     */
    private ModelDecision parseNoMatchDecision(JsonNode root) {
        requireOnlyFields(root, Set.of("decision", "reasonCode"));
        String reasonCode = requireText(root, "reasonCode");

        log.debug("Parsed NO_MATCH decision: reasonCode={}", reasonCode);
        return new ModelDecision.NoMatchDecision(reasonCode);
    }

    private String requireText(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            throw new RuntimeException("Invalid LLM response: '" + field
                    + "' must be a non-blank string");
        }
        return node.asText();
    }

    private void requireOnlyFields(JsonNode root, Set<String> allowedFields) {
        root.fieldNames().forEachRemaining(field -> {
            if (!allowedFields.contains(field)) {
                throw new RuntimeException(
                        "Invalid LLM response: unexpected field '" + field + "'");
            }
        });
    }
}
