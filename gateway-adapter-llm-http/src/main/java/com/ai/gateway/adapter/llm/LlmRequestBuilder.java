package com.ai.gateway.adapter.llm;

import com.ai.gateway.domain.port.LlmRouterPort.LlmCandidate;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * Constructs the restricted candidate context for LLM routing requests
 *
 * <p>The model receives only the authorized Top-K candidates, each with a
 * short alias and public description. The request context must NOT include
 * protocol bindings, service addresses, interface class names, tenant
 * identity, serialization methods, timeout, or retry configuration.</p>
 *
 * <p>The builder produces a JSON string suitable for the LLM API request
 * body. The candidate context contains:</p>
 * <ul>
 * <li>Short alias (e.g., {@code cap_7k3m2v6p4a9d1f8q})</li>
 * <li>Public description: displayName, description, positive/negative
 * examples, synonyms</li>
 * <li>Public input Schema (MODEL-source business fields only)</li>
 * </ul>
 *
 * @since 0.1.0
 */
@Component
public class LlmRequestBuilder {

    private static final Logger log = LoggerFactory.getLogger(LlmRequestBuilder.class);

    private final ObjectMapper objectMapper;

    /**
     * Constructs a new LlmRequestBuilder with the default ObjectMapper.
     */
    public LlmRequestBuilder() {
        this(new ObjectMapper());
    }

    /**
     * Constructs a new LlmRequestBuilder with a custom ObjectMapper.
     *
     * @param objectMapper the JSON serializer
     */
    public LlmRequestBuilder(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper,
                "objectMapper must not be null");
    }

    /**
     * Builds the LLM API request body as a JSON string.
     *
     * <p>The request contains only the restricted candidate context:
     * short alias, public description (displayName, description, examples,
     * synonyms), and public input Schema. It does NOT include protocol
     * bindings, service addresses, interface class names, tenant identity,
     * serialization, timeout, or retry configuration.</p>
     *
     * @param userText the user's natural-language request text
     * @param candidates the authorized Top-K candidate capabilities
     * @return the JSON string for the LLM API request body
     * @throws java.lang.NullPointerException if userText or candidates is null
     */
    public String buildRequest(String userText, List<LlmCandidate> candidates) {
        Objects.requireNonNull(userText, "userText must not be null");
        Objects.requireNonNull(candidates, "candidates must not be null");

        try {
            ObjectNode rootNode = objectMapper.createObjectNode();

            // System prompt constrains the LLM to only select from provided
            // candidates
            rootNode.put("system",
                    "You are a capability routing assistant. Select exactly one candidate "
                            + "from the provided list and generate arguments conforming to "
                            + "that candidate's inputSchema. Return one of: SELECT, CLARIFY, "
                            + "or NO_MATCH. You may ONLY select from the provided aliases.");

            // User text
            rootNode.put("userText", userText);

            // Restricted candidate context
            ArrayNode candidatesArray = rootNode.putArray("candidates");
            for (LlmCandidate candidate : candidates) {
                ObjectNode candidateNode = candidatesArray.addObject();
                // Short alias - does not expose real capabilityId
                candidateNode.put("alias", candidate.alias());
                // Public description only
                candidateNode.put("displayName", candidate.displayName());
                candidateNode.put("description", candidate.description());

                // Positive examples
                if (candidate.positiveExamples() != null) {
                    ArrayNode posArray = candidateNode.putArray("positiveExamples");
                    for (String ex : candidate.positiveExamples()) {
                        posArray.add(ex);
                    }
                }

                // Negative examples
                if (candidate.negativeExamples() != null) {
                    ArrayNode negArray = candidateNode.putArray("negativeExamples");
                    for (String ex : candidate.negativeExamples()) {
                        negArray.add(ex);
                    }
                }

                // Synonyms
                if (candidate.synonyms() != null) {
                    ArrayNode synArray = candidateNode.putArray("synonyms");
                    for (String syn : candidate.synonyms()) {
                        synArray.add(syn);
                    }
                }

                // Public input Schema (MODEL fields only) - embedded as raw JSON
                if (candidate.inputSchema() != null && !candidate.inputSchema().isEmpty()) {
                    candidateNode.set("inputSchema",
                            objectMapper.valueToTree(candidate.inputSchema()));
                }

                // Deliberately NOT included: protocol binding, service address,
                // interface class name, tenant, user identity, serialization,
                // timeout, retry
            }

            String json = objectMapper.writeValueAsString(rootNode);
            log.debug("Built LLM request with {} candidates", candidates.size());
            return json;
        } catch (Exception e) {
            throw new RuntimeException("Failed to build LLM request body", e);
        }
    }
}
