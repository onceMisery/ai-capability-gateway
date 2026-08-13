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
 * HTTP-based implementation of {@link LlmRouterPort} using JDK HttpClient
 *
 * <p>This adapter calls the LLM API using {@link java.net.http.HttpClient},
 * sends the restricted candidate context, and parses the model's response.
 * It uses structured output or Function Calling and performs local Schema
 * validation on the final JSON.</p>
 *
 * <p>Key security constraints:</p>
 * <ul>
 * <li>Model provider response content is NOT logged in full.</li>
 * <li>If the LLM is unavailable, returns a clear error or routes to
 * manual entry — it must not degrade to guessing interfaces.</li>
 * <li>Prompt templates, model IDs, temperature, and parser versions are
 * versioned.</li>
 * </ul>
 *
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
     * Constructs a new HttpLlmRouterAdapter.
     *
     * @param endpoint the LLM API endpoint URL
     * @param apiKey the API key for authentication
     * @param model the model identifier (versioned)
     * @param temperature the sampling temperature (versioned)
     * @param maxTokens the maximum tokens in the response
     * @param requestBuilder the request builder for restricted candidate context
     * @param responseParser the response parser for model decisions
     * @param templateRegistry the prompt template registry
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
            // Step 1: Build the restricted candidate context
            String candidateContext = requestBuilder.buildRequest(userText, candidates);

            // Step 2: Build the LLM API request body
            String requestBody = buildApiRequestBody(candidateContext);

            // Step 3: Send HTTP POST to LLM endpoint
            String responseBody = sendRequest(requestBody);

            // Step 4: Extract model content from API response
            String modelContent = extractModelContent(responseBody);

            // Step 5: Local Schema validation and parse
            ModelDecision decision = responseParser.parse(modelContent);

            // Step 6: Validate decision against candidate set
            // The gateway performs deterministic checks after the model returns.
            // Alias validation against candidate set is done here as a first gate.
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
     * Builds the LLM API request body in chat completions format.
     *
     * <p>The system prompt constrains the LLM to only select from provided
     * candidates. The user message contains the restricted candidate context
     * from {@link LlmRequestBuilder}.</p>
     *
     * @param candidateContext the restricted candidate context JSON string
     * @return the API request body JSON string
     */
    private String buildApiRequestBody(String candidateContext) {
        try {
            ObjectNode rootNode = objectMapper.createObjectNode();
            rootNode.put("model", model);
            rootNode.put("temperature", temperature);
            rootNode.put("max_tokens", maxTokens);

            // Messages: system prompt + user message with candidate context
            ArrayNode messagesArray = rootNode.putArray("messages");

            // System prompt from registry (versioned)
            String systemPrompt = templateRegistry.getTemplate("default-system");
            if (systemPrompt != null) {
                ObjectNode systemMessage = messagesArray.addObject();
                systemMessage.put("role", "system");
                systemMessage.put("content", systemPrompt);
            }

            // User message with restricted candidate context
            ObjectNode userMessage = messagesArray.addObject();
            userMessage.put("role", "user");
            userMessage.put("content", candidateContext);

            return objectMapper.writeValueAsString(rootNode);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build LLM API request body", e);
        }
    }

    /**
     * Sends the HTTP POST request to the LLM endpoint.
     *
     * <p>Model provider response content is NOT logged in full.
     * Only the HTTP status code and structural metadata are logged.</p>
     *
     * @param requestBody the API request body JSON string
     * @return the response body string
     * @throws LlmRouterPort.LlmRoutingException if the LLM is unreachable or returns
     * a server error
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
                // LLM unavailable — do not log full response body
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
     * Extracts the model's content from the API response.
     *
     * <p>Standard chat completions responses contain the model output in
     * {@code choices[0].message.content}. If the response is already a
     * direct decision JSON (not wrapped in choices), it is returned as-is.</p>
     *
     * @param responseBody the raw API response body
     * @return the model's content string (the decision JSON)
     */
    private String extractModelContent(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);

            // Standard chat completions format: choices[0].message.content
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

            // Fallback: the response body itself is the decision JSON
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
     * Validates that the SELECT decision's alias belongs to the provided
     * candidate set.
     *
     * <p>This is the first deterministic check after the model returns. The
     * gateway performs additional checks (authorization, capability state,
     * Schema validation) in subsequent pipeline stages.</p>
     *
     * @param decision the model's decision
     * @param candidates the authorized candidate set
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
     * Gracefully shuts down the HTTP client.
     */
    @PreDestroy
    public void shutdown() {
        log.info("Shutting down HttpLlmRouterAdapter...");
        // HttpClient in JDK 21 manages its own resources and is auto-closable
        log.info("HttpLlmRouterAdapter shutdown complete");
    }

}
