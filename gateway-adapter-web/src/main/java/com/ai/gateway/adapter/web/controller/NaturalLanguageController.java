package com.ai.gateway.adapter.web.controller;

import com.ai.gateway.application.runtime.ClarificationUseCase;
import com.ai.gateway.application.runtime.NaturalLanguageQueryUseCase;
import com.ai.gateway.adapter.web.support.RequestContextFactory;
import com.ai.gateway.domain.model.RequestContext;
import com.ai.gateway.domain.model.Principal;
import com.ai.gateway.domain.port.AuthenticationPort;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * REST controller for the natural-language query API.
 *
 * <p>This controller exposes two endpoints:</p>
 * <ul>
 * <li>{@code POST /api/v1/natural-language/queries} — accepts a natural-language
 * query and routes it through the 11-step pipeline.</li>
 * <li>{@code POST /api/v1/natural-language/interactions/{interactionId}/messages}
 * — continues a clarification session with additional user input
 *.</li>
 * </ul>
 *
 * <p>The response format follows :</p>
 * <ul>
 * <li>{@code COMPLETED} — HTTP 200 with structured result data.</li>
 * <li>{@code CLARIFICATION_REQUIRED} — HTTP 200 with a clarification question
 * and interactionId.</li>
 * <li>{@code NO_MATCH} — HTTP 200 with a no-match indicator.</li>
 * <li>{@code ERROR} — HTTP 200 with a stable error code and message.</li>
 * </ul>
 *
 * <p>The controller never exposes: the original Prompt, the full candidate set,
 * the protocol binding, or any internal stack trace.</p>
 *
 * @since 0.1.0
 */
@RestController
@RequestMapping("/api/v1/natural-language")
public class NaturalLanguageController {

    private static final Logger log = LoggerFactory.getLogger(NaturalLanguageController.class);

    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final NaturalLanguageQueryUseCase nlQueryUseCase;
    private final ClarificationUseCase clarificationUseCase;
    private final RequestContextFactory requestContextFactory;
    private final AuthenticationPort authenticationPort;

    /**
     * Constructs a new NaturalLanguageController.
     *
     * @param nlQueryUseCase the natural-language query use case
     * @param clarificationUseCase the clarification continuation use case
     * @param requestContextFactory the factory adapting servlet requests to
     * the domain {@link RequestContext}
     * @throws NullPointerException if any argument is null
     */
    public NaturalLanguageController(NaturalLanguageQueryUseCase nlQueryUseCase,
                                     ClarificationUseCase clarificationUseCase,
                                     RequestContextFactory requestContextFactory,
                                     AuthenticationPort authenticationPort) {
        this.nlQueryUseCase = Objects.requireNonNull(nlQueryUseCase,
                "nlQueryUseCase must not be null");
        this.clarificationUseCase = Objects.requireNonNull(clarificationUseCase,
                "clarificationUseCase must not be null");
        this.requestContextFactory = Objects.requireNonNull(requestContextFactory,
                "requestContextFactory must not be null");
        this.authenticationPort = Objects.requireNonNull(authenticationPort,
                "authenticationPort must not be null");
    }

    /**
     * Accepts a natural-language query and routes it through the complete
     * 11-step pipeline.
     *
     * @param request the query request containing requestId, text, locale, timezone
     * @param authHeader the Authorization header (Bearer token)
     * @param servletRequest the underlying servlet request used to build the
     * {@link RequestContext}
     * @return the query result in the response format
     */
    @PostMapping("/queries")
    public ResponseEntity<Map<String, Object>> query(
            @Valid @RequestBody QueryRequest request,
            @RequestHeader(value = AUTH_HEADER) String authHeader,
            HttpServletRequest servletRequest) {

        RequestContext requestContext = requestContextFactory.from(servletRequest);
        log.info("NL query received: requestId={}, locale={}", request.requestId(), request.locale());

        NaturalLanguageQueryUseCase.QueryResult result =
                nlQueryUseCase.execute(requestContext, request.requestId(),
                        request.text(), request.locale(),
                        request.timezone() != null ? request.timezone() : "UTC");

        return buildQueryResponse(result, request.requestId());
    }

    /**
     * Continues a clarification session with additional user input
     *
     * @param interactionId the clarification interaction ID
     * @param request the message request containing the user's additional text
     * @param authHeader the Authorization header (Bearer token)
     * @return the clarification result in the response format
     */
    @PostMapping("/interactions/{interactionId}/messages")
    public ResponseEntity<Map<String, Object>> continueClarification(
            @PathVariable String interactionId,
            @Valid @RequestBody ClarificationMessageRequest request,
            @RequestHeader(value = AUTH_HEADER) String authHeader,
            HttpServletRequest servletRequest) {

        log.info("Clarification continuation: interactionId={}", interactionId);

        Principal principal = authenticationPort.authenticate(
                requestContextFactory.from(servletRequest));
        String principalDigest = computePrincipalDigest(principal.subject());

        ClarificationUseCase.ClarificationResult result =
                clarificationUseCase.continueClarification(interactionId, request.text(),
                        principalDigest);

        return buildClarificationResponse(result);
    }

    /**
     * Builds the HTTP response for a query result.
     *
     * <p>The response never exposes the Prompt, the full candidate set,
     * the protocol binding, or internal stack traces.</p>
     *
     * @param result the query result from the use case
     * @return the ResponseEntity with the appropriate response format
     */
    private ResponseEntity<Map<String, Object>> buildQueryResponse(
            NaturalLanguageQueryUseCase.QueryResult result, String requestId) {

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);

        switch (result.status()) {
            case COMPLETED -> {
                body.put("status", "COMPLETED");
                body.put("data", result.data());
                body.put("summary", result.summary());
                body.put("snapshotVersion", result.snapshotVersion());
                if (result.capability() != null) body.put("capability", result.capability());
                if (result.execution() != null) body.put("execution", result.execution());
                return ResponseEntity.ok(body);
            }
            case CLARIFICATION_REQUIRED -> {
                body.put("status", "CLARIFICATION_REQUIRED");
                body.put("question", result.summary());
                body.put("interactionId", result.interactionId());
                body.put("snapshotVersion", result.snapshotVersion());
                body.put("errorCode", result.errorCode());
                if (result.expiresAt() != null) {
                    body.put("expiresAt", result.expiresAt().toString());
                }
                return ResponseEntity.ok(body);
            }
            case NO_MATCH -> {
                body.put("status", "NO_MATCH");
                body.put("snapshotVersion", result.snapshotVersion());
                body.put("errorCode", result.errorCode());
                body.put("message", "No capability matched the request");
                return ResponseEntity.ok(body);
            }
            case ERROR -> {
                body.put("status", "ERROR");
                body.put("snapshotVersion", result.snapshotVersion());
                body.put("errorCode", result.errorCode());
                body.put("message", sanitizeErrorMessage(result.errorMessage()));
                return ResponseEntity.status(mapErrorToStatus(result.errorCode())).body(body);
            }
            default -> {
                body.put("status", "ERROR");
                body.put("errorCode", "PROTOCOL_ERROR");
                body.put("message", "Unknown query status");
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
            }
        }
    }

    /**
     * Builds the HTTP response for a clarification continuation result
     *
     * @param result the clarification result from the use case
     * @return the ResponseEntity with the appropriate response format
     */
    private ResponseEntity<Map<String, Object>> buildClarificationResponse(
            ClarificationUseCase.ClarificationResult result) {

        Map<String, Object> body = new LinkedHashMap<>();

        switch (result.status()) {
            case SELECT -> {
                body.put("status", "COMPLETED");
                body.put("data", result.data());
                return ResponseEntity.ok(body);
            }
            case CLARIFY -> {
                body.put("status", "CLARIFICATION_REQUIRED");
                body.put("question", result.question());
                body.put("interactionId", result.interactionId());
                return ResponseEntity.ok(body);
            }
            case INVALID -> {
                body.put("status", "INVALID");
                body.put("message", sanitizeErrorMessage(result.errorMessage()));
                return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
            }
            case INTENT_BREAKOUT -> {
                body.put("status", "INTENT_BREAKOUT");
                body.put("message", "Full pipeline restart required");
                return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
            }
            case ERROR -> {
                body.put("status", "ERROR");
                body.put("message", sanitizeErrorMessage(result.errorMessage()));
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
            }
            default -> {
                body.put("status", "ERROR");
                body.put("message", "Unknown clarification status");
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
            }
        }
    }

    /**
     * Maps a stable error code to the appropriate HTTP status.
     *
     * @param errorCode the stable error code name
     * @return the corresponding HTTP status
     */
    private HttpStatus mapErrorToStatus(String errorCode) {
        if (errorCode == null) {
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }
        return switch (errorCode) {
            case "AUTHENTICATION_FAILED" -> HttpStatus.UNAUTHORIZED;
            case "PERMISSION_DENIED" -> HttpStatus.FORBIDDEN;
            case "NO_CAPABILITY_MATCH" -> HttpStatus.NOT_FOUND;
            case "CLARIFICATION_REQUIRED", "INVALID_MODEL_OUTPUT" -> HttpStatus.UNPROCESSABLE_ENTITY;
            case "ARGUMENT_VALIDATION_FAILED" -> HttpStatus.BAD_REQUEST;
            case "CAPABILITY_UNAVAILABLE" -> HttpStatus.SERVICE_UNAVAILABLE;
            case "LLM_UNAVAILABLE" -> HttpStatus.SERVICE_UNAVAILABLE;
            case "RATE_LIMITED" -> HttpStatus.TOO_MANY_REQUESTS;
            case "PROVIDER_TIMEOUT" -> HttpStatus.GATEWAY_TIMEOUT;
            case "PROVIDER_REJECTED" -> HttpStatus.BAD_GATEWAY;
            case "PROTOCOL_ERROR" -> HttpStatus.BAD_GATEWAY;
            case "RESULT_TOO_LARGE" -> HttpStatus.PAYLOAD_TOO_LARGE;
            case "EXECUTION_UNKNOWN" -> HttpStatus.CONFLICT;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    /**
     * Extracts the bearer token from the Authorization header.
     *
     * @param authHeader the Authorization header value
     * @return the token string, or an empty string if not present
     */
    private String extractToken(String authHeader) {
        if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
            return authHeader.substring(BEARER_PREFIX.length());
        }
        return "";
    }

    /**
     * Computes a SHA-256 digest of the token for secure session matching.
     *
     * @param authHeader the Authorization header value
     * @return the hex-encoded SHA-256 digest
     */
    private String computePrincipalDigest(String subject) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(subject.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new InternalError("SHA-256 not available", e);
        }
    }

    /**
     * Sanitizes an error message for external exposure.
     *
     * <p>Never exposes stack traces, internal addresses, interface class
     * names, or sensitive parameters.</p>
     *
     * @param message the raw error message
     * @return the sanitized message safe for external exposure
     */
    private String sanitizeErrorMessage(String message) {
        if (message == null) {
            return "An internal error occurred";
        }
        // Remove any potential stack trace fragments
        String sanitized = message.replaceAll("at\\s+\\S+\\.\\S+\\([^)]*\\)", "[internal]");
        // Remove file paths
        sanitized = sanitized.replaceAll("/\\S+\\.java:\\d+", "[internal]");
        return sanitized;
    }

    /**
     * Request body for POST /queries.
     *
     * @param requestId the client-provided request identifier
     * @param text the natural-language query text
     * @param locale the request locale (e.g., "zh-CN")
     * @param timezone the request timezone (e.g., "Asia/Shanghai")
     */
    public record QueryRequest(
            @NotBlank @Size(max = 128) String requestId,
            @NotBlank @Size(max = 8192) String text,
            @NotBlank @Size(max = 32) String locale,
            @Size(max = 64) String timezone
    ) {
    }

    /**
     * Request body for POST /interactions/{interactionId}/messages
     *
     * @param text the user's additional input text
     */
    public record ClarificationMessageRequest(
            @NotBlank @Size(max = 8192) String text
    ) {
    }
}
