package com.ai.gateway.example.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Example client demonstrating how to interact with the AI Capability Gateway API.
 *
 * <p>This client covers:
 * <ul>
 * <li>Natural language queries</li>
 * <li>Clarification session continuation</li>
 * <li>Write operation prepare/confirm</li>
 * <li>Admin operations: import, validate, approve, publish</li>
 * </ul>
 *
 * <p>Usage example:
 * <pre>{@code
 * GatewayApiClient client = new GatewayApiClient("http://localhost:8080", "my-jwt-token");
 *
 * // Natural language query
 * var result = client.naturalLanguageQuery("查询订单 SO202607210001", "zh-CN");
 * System.out.println(result);
 *
 * // Admin: import manifest
 * client.importManifest(manifestYaml);
 * }</pre>
 *
 * <p>This client uses only JDK {@link HttpClient} and Jackson
 * for JSON processing. No Spring or other framework dependencies are required.</p>
 *
 * @since 0.1.0
 */
public class GatewayApiClient {

    private static final Logger log = LoggerFactory.getLogger(GatewayApiClient.class);

    private static final String CONTENT_TYPE_JSON = "application/json";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private final String baseUrl;
    private final String authToken;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    /**
     * Constructs a new GatewayApiClient.
     *
     * @param baseUrl the gateway base URL (e.g., "http://localhost:8080")
     * @param authToken the JWT or SSO bearer token for authentication
     * @throws NullPointerException if baseUrl or authToken is null
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
     * Sends a natural-language query to the gateway.
     *
     * <p>Endpoint: {@code POST /api/v1/natural-language/queries}</p>
     *
     * <p>The response status field indicates the outcome:</p>
     * <ul>
     * <li>{@code COMPLETED} — query executed successfully, data is in "data" field.</li>
     * <li>{@code CLARIFICATION_REQUIRED} — additional input needed, use
     * {@link #continueClarification(String, String)} with the returned interactionId.</li>
     * <li>{@code NO_MATCH} — no capability matched the query.</li>
     * <li>{@code ERROR} — an error occurred, check "errorCode" and "message".</li>
     * </ul>
     *
     * @param text the natural-language query text (e.g., "查询订单 SO202607210001")
     * @param locale the request locale (e.g., "zh-CN")
     * @return the parsed JSON response as a Map
     * @throws GatewayApiException if the request fails or the response cannot be parsed
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
     * Continues a clarification session with additional user input.
     *
     * <p>Endpoint: {@code POST /api/v1/natural-language/interactions/{interactionId}/messages}</p>
     *
     * <p>When a query returns {@code CLARIFICATION_REQUIRED}, the response includes
     * an {@code interactionId}. Use this method to provide the missing information.</p>
     *
     * <p>Important constraints:</p>
     * <ul>
     * <li>Subsequent answers may only supplement missing information or
     * disambiguate within the original candidate set.</li>
     * <li>If the user's reply triggers a NO_MATCH or selects an alias outside
     * the original candidate set, the interactionId is invalidated and a
     * full routing pipeline restart is required.</li>
     * <li>Principal change, session expiry, capability suspension, or policy
     * change also forces a fresh start.</li>
     * </ul>
     *
     * @param interactionId the clarification interaction ID from a previous response
     * @param text the user's additional input text
     * @return the parsed JSON response as a Map
     * @throws GatewayApiException if the request fails or the response cannot be parsed
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
     * Prepares a write operation for confirmation.
     *
     * <p>Endpoint: {@code POST /api/v1/natural-language/actions:prepare}</p>
     *
     * <p>The Prepare phase performs: parameter binding, authorization check,
     * and persists an immutable operation record. A short-lived confirmation
     * token is issued for the Confirm phase.</p>
     *
     * <p>The response includes:</p>
     * <ul>
     * <li>{@code operationId} — the unique operation identifier.</li>
     * <li>{@code confirmationToken} — the token required for the Confirm phase.</li>
     * <li>{@code summary} — a human-readable summary of the operation.</li>
     * <li>{@code expiresAt} — when the confirmation token expires.</li>
     * </ul>
     *
     * @param text the natural-language write request (e.g., "取消订单 SO202607210001")
     * @return the parsed JSON response as a Map
     * @throws GatewayApiException if the request fails or the response cannot be parsed
     */
    public Map<String, Object> prepareAction(String text) {
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("text", text);
        requestBody.put("locale", "zh-CN");
        requestBody.put("timezone", "Asia/Shanghai");

        return post("/api/v1/natural-language/actions:prepare", requestBody);
    }

    /**
     * Confirms and executes a prepared write operation.
     *
     * <p>Endpoint: {@code POST /api/v1/operations/{operationId}:confirm}</p>
     *
     * <p>The Confirm phase atomically claims execution using the confirmation
     * token and invokes the Provider. The response includes the final operation
     * state (SUCCEEDED, FAILED, UNKNOWN, etc.).</p>
     *
     * @param operationId the operation ID from the Prepare phase
     * @param confirmToken the confirmation token from the Prepare phase
     * @return the parsed JSON response as a Map
     * @throws GatewayApiException if the request fails or the response cannot be parsed
     */
    public Map<String, Object> confirmOperation(String operationId, String confirmToken) {
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("token", confirmToken);

        return post("/api/v1/operations/" + operationId + ":confirm", requestBody);
    }

    /**
     * Queries the current status of a write operation.
     *
     * <p>Endpoint: {@code GET /api/v1/operations/{operationId}}</p>
     *
     * <p>The response includes the operation state, which follows the state
     * machine defined in :</p>
     * <pre>
     * PREPARED -> EXECUTING -> SUCCEEDED
     * | |----> FAILED
     * | +----> UNKNOWN -> SUCCEEDED / FAILED / MANUAL_REVIEW
     * |-----------------> EXPIRED
     * +-----------------> CANCELLED
     * </pre>
     *
     * @param operationId the operation identifier
     * @return the parsed JSON response as a Map
     * @throws GatewayApiException if the request fails or the response cannot be parsed
     */
    public Map<String, Object> getOperationStatus(String operationId) {
        return get("/api/v1/operations/" + operationId);
    }

    // ========================================================================
    // — Admin / Control Plane API
    // ========================================================================

    /**
     * Imports a Capability Manifest through the 10-step validation pipeline
     *
     * <p>Endpoint: {@code POST /admin/v1/manifests:import}</p>
     *
     * <p>The manifest is validated against the versioned JSON Schema and the
     * 10-step pipeline including: Schema validation, ID/version format check,
     * input Schema security constraints, parameter binding consistency,
     * serialization whitelist, output contract validation, and more.</p>
     *
     * @param manifestYaml the Capability Manifest in YAML format
     * @return the parsed JSON response as a Map containing status and validationReport
     * @throws GatewayApiException if the request fails or the response cannot be parsed
     */
    public Map<String, Object> importManifest(String manifestYaml) {
        // The admin import endpoint accepts the manifest as a JSON body.
        // In a real scenario, the YAML would be converted to JSON first,
        // or the endpoint would accept YAML content-type.
        // Here we send the raw YAML string as the body with a YAML content type.
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
     * Re-validates an existing manifest version.
     *
     * <p>Endpoint: {@code POST /admin/v1/capabilities/{id}/versions/{version}:validate}</p>
     *
     * @param id the capability identifier (e.g., "order.detail.query")
     * @param version the semantic version (e.g., "1.0.0")
     * @return the parsed JSON response as a Map containing validation status
     * @throws GatewayApiException if the request fails or the response cannot be parsed
     */
    public Map<String, Object> validateCapability(String id, String version) {
        return post("/admin/v1/capabilities/" + id + "/versions/" + version + ":validate",
                Map.of());
    }

    /**
     * Approves a validated manifest.
     *
     * <p>Endpoint: {@code POST /admin/v1/capabilities/{id}/versions/{version}:approve}</p>
     *
     * <p>Approval transitions the manifest from VALIDATED to APPROVED state,
     * making it eligible for publication.</p>
     *
     * @param id the capability identifier
     * @param version the semantic version
     * @return the parsed JSON response as a Map containing approval status
     * @throws GatewayApiException if the request fails or the response cannot be parsed
     */
    public Map<String, Object> approveCapability(String id, String version) {
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("approver", "admin@example.com");

        return post("/admin/v1/capabilities/" + id + "/versions/" + version + ":approve",
                requestBody);
    }

    /**
     * Publishes a new catalog snapshot to the specified environment
     *
     * <p>Endpoint: {@code POST /admin/v1/releases:publish}</p>
     *
     * <p>Publication generates an immutable snapshot containing all APPROVED
     * capabilities. The snapshot version is monotonically increasing and
     * the snapshot content cannot be modified after creation.</p>
     *
     * @param environment the target environment (e.g., "production", "staging")
     * @return the parsed JSON response as a Map containing the new snapshotVersion
     * @throws GatewayApiException if the request fails or the response cannot be parsed
     */
    public Map<String, Object> publishRelease(String environment) {
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("environment", environment);

        return post("/admin/v1/releases:publish", requestBody);
    }

    /**
     * Suspends a capability immediately.
     *
     * <p>Endpoint: {@code POST /admin/v1/capabilities/{id}:suspend}</p>
     *
     * <p>Suspension is an emergency operation that immediately removes the
     * capability from the active catalog snapshot. A new snapshot version
     * is generated without the suspended capability.</p>
     *
     * @param id the capability identifier to suspend
     * @param reason the suspension reason (for audit trail)
     * @return the parsed JSON response as a Map containing suspension status
     * @throws GatewayApiException if the request fails or the response cannot be parsed
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
     * Checks the gateway health status.
     *
     * <p>Endpoint: {@code GET /health/readiness}</p>
     *
     * <p>The readiness probe checks: database connectivity, active snapshot
     * loaded, required secrets available, and adapter initialization.</p>
     *
     * @return the parsed JSON response as a Map containing health check results
     * @throws GatewayApiException if the request fails or the response cannot be parsed
     */
    public Map<String, Object> getHealth() {
        return get("/health/readiness");
    }

    // ========================================================================
    // Internal HTTP helpers
    // ========================================================================

    /**
     * Sends a POST request with a JSON body and returns the parsed response.
     *
     * @param path the API path (appended to baseUrl)
     * @param requestBody the request body to serialize as JSON
     * @return the parsed JSON response as a Map
     * @throws GatewayApiException if the request fails or the response cannot be parsed
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
     * Sends a GET request and returns the parsed response.
     *
     * @param path the API path (appended to baseUrl)
     * @return the parsed JSON response as a Map
     * @throws GatewayApiException if the request fails or the response cannot be parsed
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
     * Parses an HTTP response body as a JSON Map.
     *
     * <p>If the response body is empty or not valid JSON, a descriptive
     * error map is returned instead of throwing an exception, allowing
     * callers to inspect the HTTP status code.</p>
     *
     * @param response the HTTP response
     * @return the parsed response body as a Map
     * @throws GatewayApiException if the response body cannot be parsed
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
     * Truncates a string to the specified maximum length for logging.
     *
     * @param value the string to truncate
     * @param maxLength the maximum length
     * @return the truncated string with "..." suffix if truncated
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
     * Exception thrown when a gateway API call fails.
     *
     * <p>This wraps transport errors, JSON parsing errors, and other
     * failures with descriptive messages.</p>
     */
    public static class GatewayApiException extends RuntimeException {

        /**
         * Constructs a new GatewayApiException.
         *
         * @param message the error message
         * @param cause the underlying cause
         */
        public GatewayApiException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
