package com.ai.gateway.adapter.web.controller;

import com.ai.gateway.application.operation.OperationConfirmUseCase;
import com.ai.gateway.application.operation.OperationPrepareUseCase;
import com.ai.gateway.application.operation.OperationStatusUseCase;
import com.ai.gateway.adapter.web.support.RequestContextFactory;
import com.ai.gateway.domain.model.OperationRecord;
import com.ai.gateway.domain.model.OperationState;
import com.ai.gateway.domain.model.Principal;
import com.ai.gateway.domain.model.RequestContext;
import com.ai.gateway.domain.port.AuthenticationPort;
import com.ai.gateway.domain.port.OperationRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * REST controller for the write-operation API.
 *
 * <p>This controller exposes four endpoints for the two-phase Prepare/Confirm
 * write-operation protocol:</p>
 * <ul>
 * <li>{@code POST /api/v1/natural-language/actions:prepare} — prepares a
 * write operation and returns a confirmation token.</li>
 * <li>{@code POST /api/v1/operations/{operationId}:confirm} — confirms and
 * executes a prepared operation using the confirmation token
 *.</li>
 * <li>{@code POST /api/v1/operations/{operationId}:cancel} — cancels a
 * prepared operation before confirmation.</li>
 * <li>{@code GET /api/v1/operations/{operationId}} — queries the current
 * status of a write operation.</li>
 * </ul>
 *
 * <p>The controller never exposes encrypted arguments, server signatures,
 * or internal stack traces.</p>
 *
 * @since 0.1.0
 */
@RestController
@RequestMapping("/api/v1")
public class OperationController {

    private static final Logger log = LoggerFactory.getLogger(OperationController.class);

    private static final String AUTH_HEADER = "Authorization";

    private final OperationPrepareUseCase prepareUseCase;
    private final OperationConfirmUseCase confirmUseCase;
    private final OperationStatusUseCase statusUseCase;
    private final AuthenticationPort authenticationPort;
    private final OperationRepository operationRepository;
    private final RequestContextFactory requestContextFactory;

    /**
     * Constructs a new OperationController.
     *
     * @param prepareUseCase the operation prepare use case
     * @param confirmUseCase the operation confirm use case
     * @param statusUseCase the operation status query use case
     * @param authenticationPort the authentication port for Principal construction
     * @param operationRepository the operation repository for cancel operations
     * @param requestContextFactory the factory adapting servlet requests to
     * the domain {@link RequestContext}
     * @throws NullPointerException if any argument is null
     */
    public OperationController(OperationPrepareUseCase prepareUseCase,
                                OperationConfirmUseCase confirmUseCase,
                                OperationStatusUseCase statusUseCase,
                                AuthenticationPort authenticationPort,
                                OperationRepository operationRepository,
                                RequestContextFactory requestContextFactory) {
        this.prepareUseCase = Objects.requireNonNull(prepareUseCase,
                "prepareUseCase must not be null");
        this.confirmUseCase = Objects.requireNonNull(confirmUseCase,
                "confirmUseCase must not be null");
        this.statusUseCase = Objects.requireNonNull(statusUseCase,
                "statusUseCase must not be null");
        this.authenticationPort = Objects.requireNonNull(authenticationPort,
                "authenticationPort must not be null");
        this.operationRepository = Objects.requireNonNull(operationRepository,
                "operationRepository must not be null");
        this.requestContextFactory = Objects.requireNonNull(requestContextFactory,
                "requestContextFactory must not be null");
    }

    /**
     * Prepares a write operation for confirmation.
     *
     * @param request the prepare request containing text, locale, timezone
     * @param authHeader the Authorization header (Bearer token)
     * @param servletRequest the underlying servlet request used to build the
     * {@link RequestContext}
     * @return the prepare result with operationId, confirmation token, and summary
     */
    @PostMapping("/natural-language/actions:prepare")
    public ResponseEntity<Map<String, Object>> prepare(
            @RequestBody @Valid PrepareRequest request,
            @RequestHeader(value = AUTH_HEADER, required = false) String authHeader,
            HttpServletRequest servletRequest) {

        RequestContext requestContext = requestContextFactory.from(servletRequest);
        log.info("Prepare phase requested");

        OperationPrepareUseCase.PrepareResult result =
                prepareUseCase.prepare(requestContext, request.text(),
                        request.locale() != null ? request.locale() : "zh-CN",
                        request.timezone() != null ? request.timezone() : "UTC");

        Map<String, Object> body = new LinkedHashMap<>();

        if (result.success()) {
            body.put("status", "PREPARED");
            body.put("operationId", result.operationId());
            body.put("confirmationToken", result.token() != null ? result.token().token() : null);
            body.put("summary", result.summary());
            body.put("expiresAt", result.expiresAt());
            return ResponseEntity.ok(body);
        } else {
            body.put("status", "FAILED");
            body.put("message", sanitizeErrorMessage(result.error()));
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
        }
    }

    /**
     * Confirms and executes a prepared write operation.
     *
     * @param operationId the operation identifier from the Prepare phase
     * @param request the confirm request containing the confirmation token
     * @param authHeader the Authorization header (Bearer token)
     * @param servletRequest the underlying servlet request used to build the
     * {@link RequestContext}
     * @return the confirm result with the final operation state
     */
    @PostMapping("/operations/{operationId}:confirm")
    public ResponseEntity<Map<String, Object>> confirm(
            @PathVariable String operationId,
            @RequestBody @Valid ConfirmRequest request,
            @RequestHeader(value = AUTH_HEADER, required = false) String authHeader,
            HttpServletRequest servletRequest) {

        RequestContext requestContext = requestContextFactory.from(servletRequest);
        log.info("Confirm phase requested: operationId={}", operationId);

        // Authenticate the caller to construct the Principal
        Principal principal;
        try {
            principal = authenticationPort.authenticate(requestContext);
        } catch (Exception e) {
            log.warn("Authentication failed for confirm: {}", e.getMessage());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "FAILED");
            body.put("message", "Authentication failed");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
        }

        OperationConfirmUseCase.ConfirmResult result =
                confirmUseCase.confirm(operationId, request.token(), principal);

        Map<String, Object> body = new LinkedHashMap<>();

        if (result.success()) {
            body.put("status", result.finalState());
            body.put("operationId", operationId);
            body.put("message", sanitizeErrorMessage(result.message()));
            return ResponseEntity.ok(body);
        } else {
            body.put("status", result.finalState() != null ? result.finalState() : "FAILED");
            body.put("operationId", operationId);
            body.put("message", sanitizeErrorMessage(result.message()));
            return ResponseEntity.status(mapConfirmErrorToStatus(result.finalState())).body(body);
        }
    }

    /**
     * Cancels a prepared write operation before confirmation.
     *
     * @param operationId the operation identifier to cancel
     * @return the cancel result
     */
    @PostMapping("/operations/{operationId}:cancel")
    public ResponseEntity<Map<String, Object>> cancel(
            @PathVariable String operationId,
            HttpServletRequest servletRequest) {

        log.info("Cancel requested: operationId={}", operationId);

        Principal principal = authenticate(servletRequest);
        if (principal == null) {
            return authenticationFailed();
        }

        OperationRecord record = statusUseCase.query(operationId);

        Map<String, Object> body = new LinkedHashMap<>();

        if (record == null) {
            body.put("status", "NOT_FOUND");
            body.put("message", "Operation not found");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
        }

        if (!isOwner(record, principal)) {
            body.put("status", "NOT_FOUND");
            body.put("message", "Operation not found");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
        }

        if (record.state() != OperationState.PREPARED) {
            body.put("status", record.state().name());
            body.put("message", "Operation cannot be cancelled in state: " + record.state());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
        }

        boolean cancelled = operationRepository.casUpdateState(
                operationId, OperationState.PREPARED, OperationState.CANCELLED,
                record.version());

        if (cancelled) {
            body.put("status", "CANCELLED");
            body.put("operationId", operationId);
            return ResponseEntity.ok(body);
        } else {
            // CAS failed — another request may have confirmed or expired it
            OperationRecord current = statusUseCase.query(operationId);
            body.put("status", current != null ? current.state().name() : "UNKNOWN");
            body.put("message", "Operation state changed concurrently");
            return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
        }
    }

    /**
     * Queries the current status of a write operation.
     *
     * @param operationId the operation identifier
     * @return the operation status
     */
    @GetMapping("/operations/{operationId}")
    public ResponseEntity<Map<String, Object>> getStatus(
            @PathVariable String operationId,
            HttpServletRequest servletRequest) {

        log.debug("Status query: operationId={}", operationId);

        Principal principal = authenticate(servletRequest);
        if (principal == null) {
            return authenticationFailed();
        }

        OperationRecord record = statusUseCase.query(operationId);

        Map<String, Object> body = new LinkedHashMap<>();

        if (record == null) {
            body.put("status", "NOT_FOUND");
            body.put("message", "Operation not found");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
        }


        if (!isOwner(record, principal)) {
            body.put("status", "NOT_FOUND");
            body.put("message", "Operation not found");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
        }

        body.put("status", record.state().name());
        body.put("operationId", record.operationId());
        body.put("capabilityId", record.capabilityId());
        body.put("capabilityVersion", record.capabilityVersion());
        body.put("snapshotVersion", record.snapshotVersion());
        body.put("expiresAt", record.expiresAt());

        // Never expose encrypted arguments, argumentsDigest, or principalDigest
        return ResponseEntity.ok(body);
    }

    private Principal authenticate(HttpServletRequest servletRequest) {
        try {
            return authenticationPort.authenticate(requestContextFactory.from(servletRequest));
        } catch (RuntimeException e) {
            log.warn("Operation authentication failed");
            return null;
        }
    }

    private ResponseEntity<Map<String, Object>> authenticationFailed() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "FAILED");
        body.put("message", "Authentication failed");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
    }

    private boolean isOwner(OperationRecord record, Principal principal) {
        return record.orgId() == principal.orgId()
                && record.principalDigest().equals(subjectDigest(principal.subject()));
    }

    private String subjectDigest(String subject) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(subject.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new InternalError("SHA-256 is unavailable", e);
        }
    }

    /**
     * Maps a confirm result final state to the appropriate HTTP status.
     *
     * @param finalState the final state name
     * @return the corresponding HTTP status
     */
    private HttpStatus mapConfirmErrorToStatus(String finalState) {
        if (finalState == null) {
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }
        return switch (finalState) {
            case "EXPIRED" -> HttpStatus.GONE;
            case "FAILED" -> HttpStatus.INTERNAL_SERVER_ERROR;
            case "SUCCEEDED", "EXECUTING", "PREPARED" -> HttpStatus.OK;
            default -> HttpStatus.CONFLICT;
        };
    }

    /**
     * Sanitizes an error message for external exposure.
     *
     * @param message the raw error message
     * @return the sanitized message safe for external exposure
     */
    private String sanitizeErrorMessage(String message) {
        if (message == null) {
            return "An internal error occurred";
        }
        String sanitized = message.replaceAll("at\\s+\\S+\\.\\S+\\([^)]*\\)", "[internal]");
        sanitized = sanitized.replaceAll("/\\S+\\.java:\\d+", "[internal]");
        return sanitized;
    }

    /**
     * Request body for POST /natural-language/actions:prepare.
     *
     * @param text the natural-language request text
     * @param locale the request locale
     * @param timezone the request timezone
     */
    public record PrepareRequest(
            @NotBlank
            @Size(max = 4096)
            String text,
            @Size(max = 32)
            String locale,
            @Size(max = 64)
            String timezone
    ) {
    }

    /**
     * Request body for POST /operations/{operationId}:confirm.
     *
     * @param token the confirmation token string from the Prepare phase
     */
    public record ConfirmRequest(
            @NotBlank
            @Size(max = 8192)
            String token
    ) {
    }
}
