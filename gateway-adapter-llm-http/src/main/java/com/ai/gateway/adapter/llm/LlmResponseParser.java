package com.ai.gateway.adapter.llm;

import com.ai.gateway.domain.model.ModelDecision;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Parses the LLM response into a {@link ModelDecision}.
 *
 * <p>The model must return exactly one of three decision types. This parser
 * extracts the decision field and constructs the appropriate
 * {@link ModelDecision} subtype:</p>
 * <ul>
 * <li>{@code SELECT}: extracts alias and arguments</li>
 * <li>{@code CLARIFY}: extracts question</li>
 * <li>{@code NO_MATCH}: extracts reasonCode</li>
 * </ul>
 *
 * <p>Model provider response content is NOT logged in full.
 * Only structural metadata (decision type, alias, error indicators) is
 * logged at appropriate levels.</p>
 *
 * @since 0.1.0
 */
@Component
public class LlmResponseParser {

    private static final Logger log = LoggerFactory.getLogger(LlmResponseParser.class);

    private final ObjectMapper objectMapper;

    /**
     * Constructs a new LlmResponseParser with the default ObjectMapper.
     */
    public LlmResponseParser() {
        this(new ObjectMapper());
    }

    /**
     * Constructs a new LlmResponseParser with a custom ObjectMapper.
     *
     * @param objectMapper the JSON deserializer
     */
    public LlmResponseParser(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper,
                "objectMapper must not be null");
    }

    /**
     * Parses the LLM response body into a {@link ModelDecision}.
     *
     * <p>The response must contain a {@code decision} field with one of:
     * {@code SELECT}, {@code CLARIFY}, or {@code NO_MATCH}. The parser
     * extracts the corresponding fields based on the decision type.</p>
     *
     * @param responseBody the raw LLM response body as a JSON string
     * @return the parsed model decision; never {@code null}
     * @throws RuntimeException if the response cannot be parsed or the
     * decision field is missing/invalid
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
     * Parses a SELECT decision from the response root.
     *
     * @param root the response root node
     * @return a {@link ModelDecision.SelectDecision}
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
     * Parses a CLARIFY decision from the response root.
     *
     * @param root the response root node
     * @return a {@link ModelDecision.ClarifyDecision}
     */
    private ModelDecision parseClarifyDecision(JsonNode root) {
        requireOnlyFields(root, Set.of("decision", "question"));
        String question = requireText(root, "question");
        log.debug("Parsed CLARIFY decision");
        return new ModelDecision.ClarifyDecision(question);
    }

    /**
     * Parses a NO_MATCH decision from the response root.
     *
     * @param root the response root node
     * @return a {@link ModelDecision.NoMatchDecision}
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
