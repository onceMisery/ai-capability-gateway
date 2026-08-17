package com.ai.gateway.application.operation;

import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.ConfirmationSummary;
import com.ai.gateway.domain.model.ConfirmationToken;
import com.ai.gateway.domain.model.OperationRecord;
import com.ai.gateway.domain.model.OperationState;
import com.ai.gateway.domain.model.Principal;
import com.ai.gateway.domain.model.RequestContext;
import com.ai.gateway.domain.port.AuthorizationPort;
import com.ai.gateway.domain.port.ArgumentPayloadCodec;
import com.ai.gateway.domain.port.AuthenticationPort;
import com.ai.gateway.domain.port.CatalogPort;
import com.ai.gateway.domain.port.ConfirmationTokenCodec;
import com.ai.gateway.domain.port.EncryptionPort;
import com.ai.gateway.domain.port.OperationRepository;
import com.ai.gateway.domain.port.SchemaValidator;
import com.ai.gateway.domain.port.TypeConverterRegistry;
import com.ai.gateway.domain.service.ArgumentBinder;
import com.ai.gateway.domain.service.ManifestDigest;
import com.ai.gateway.domain.service.Sha256Digest;
import com.ai.gateway.application.runtime.NaturalLanguageQueryUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Use case for the Prepare phase of the write-operation two-phase protocol
 *
 * <p>The Prepare phase:</p>
 * <ol>
 * <li>Complete natural-language routing but do not invoke the write
 * interface.</li>
 * <li>Validate and bind complete parameters.</li>
 * <li>Execute authorization and optional read-only pre-check.</li>
 * <li>Generate a user-understandable redacted summary.</li>
 * <li>Generate a server-side idempotency key and parameter digest.</li>
 * <li>Persist an immutable operation record with state = PREPARED.</li>
 * <li>Return a short-lived confirmation token and expiry time.</li>
 * </ol>
 *
 * <p>The confirmation token is single-use, short-lived, and bound to the
 * operationId, Principal digest, orgId, arguments digest, and a server
 * signature. The operation record stores encrypted arguments
 * at rest; the arguments digest allows integrity verification without
 * decryption.</p>
 *
 * <p>This class uses constructor injection and contains no Spring annotations.
 * It is thread-safe: it holds no mutable per-request state.</p>
 *
 * @see NaturalLanguageQueryUseCase
 * @see OperationRepository
 * @since 0.1.0
 */
public final class OperationPrepareUseCase {

    private static final Logger log = LoggerFactory.getLogger(OperationPrepareUseCase.class);

    /**
     * Default confirmation token TTL in seconds (5 minutes).
     */
    private static final long CONFIRMATION_TOKEN_TTL_SECONDS = 300;

    private final NaturalLanguageQueryUseCase nlQueryUseCase;
    private final TypeConverterRegistry typeConverterRegistry;
    private final SchemaValidator schemaValidator;
    private final AuthorizationPort authorizationPort;
    private final EncryptionPort encryptionPort;
    private final OperationRepository operationRepository;
    private final CatalogPort catalogPort;
    private final AuthenticationPort authenticationPort;
    private final ConfirmationTokenCodec confirmationTokenCodec;
    private final ArgumentPayloadCodec argumentPayloadCodec;

    /**
     * Constructs a new OperationPrepareUseCase with the required dependencies.
     *
     * <p>Note: {@code typeConverterRegistry} and {@code schemaValidator} are
     * shared dependencies for constructing per-request {@link ArgumentBinder}
     * instances.</p>
     *
     * @param nlQueryUseCase the NL routing use case for step 1
     * @param typeConverterRegistry the type converter registry for ArgumentBinder
     * @param schemaValidator the JSON Schema validator for ArgumentBinder
     * @param authorizationPort the authorization port
     * @param encryptionPort the encryption port for parameter encryption
     * @param operationRepository the repository for persisting operation records
     * @param idempotencyKeyGenerator the idempotency key generator
     * @param catalogPort the catalog port for loading manifests
     * @throws NullPointerException if any argument is null
     */
    public OperationPrepareUseCase(NaturalLanguageQueryUseCase nlQueryUseCase,
                                    TypeConverterRegistry typeConverterRegistry,
                                    SchemaValidator schemaValidator,
                                    AuthorizationPort authorizationPort,
                                    EncryptionPort encryptionPort,
                                    OperationRepository operationRepository,
                                    CatalogPort catalogPort,
                                    AuthenticationPort authenticationPort,
                                    ConfirmationTokenCodec confirmationTokenCodec,
                                    ArgumentPayloadCodec argumentPayloadCodec) {
        this.nlQueryUseCase = Objects.requireNonNull(nlQueryUseCase,
                "nlQueryUseCase must not be null");
        this.typeConverterRegistry = Objects.requireNonNull(typeConverterRegistry,
                "typeConverterRegistry must not be null");
        this.schemaValidator = Objects.requireNonNull(schemaValidator,
                "schemaValidator must not be null");
        this.authorizationPort = Objects.requireNonNull(authorizationPort,
                "authorizationPort must not be null");
        this.encryptionPort = Objects.requireNonNull(encryptionPort,
                "encryptionPort must not be null");
        this.operationRepository = Objects.requireNonNull(operationRepository,
                "operationRepository must not be null");
        this.catalogPort = Objects.requireNonNull(catalogPort,
                "catalogPort must not be null");
        this.authenticationPort = Objects.requireNonNull(authenticationPort,
                "authenticationPort must not be null");
        this.confirmationTokenCodec = Objects.requireNonNull(confirmationTokenCodec,
                "confirmationTokenCodec must not be null");
        this.argumentPayloadCodec = Objects.requireNonNull(argumentPayloadCodec,
                "argumentPayloadCodec must not be null");
    }

    /**
     * Prepares a write operation for confirmation.
     *
     * @param requestContext the caller's request context carrying the
     * authentication credential
     * @param text the natural-language request text
     * @param locale the request locale
     * @param timezone the request timezone
     * @return the prepare result containing the operation ID and confirmation token
     * @throws NullPointerException if any argument is null
     */
    public PrepareResult prepare(RequestContext requestContext, String text, String locale,
                                 String timezone, String clientIdempotencyKey) {
        Objects.requireNonNull(requestContext, "requestContext must not be null");
        Objects.requireNonNull(text, "text must not be null");
        Objects.requireNonNull(locale, "locale must not be null");
        Objects.requireNonNull(timezone, "timezone must not be null");
        validateClientIdempotencyKey(clientIdempotencyKey);
        log.info("Prepare phase started for write operation");

        // Step 1: Complete NL routing but don't invoke write interface
        NaturalLanguageQueryUseCase.QueryResult routingResult =
                nlQueryUseCase.execute(requestContext, text, locale, timezone);

        if (routingResult.status() != NaturalLanguageQueryUseCase.QueryStatus.COMPLETED) {
            log.warn("NL routing did not complete: status={}", routingResult.status());
            return new PrepareResult(false, null, null, null, null,
                    "NL routing failed: " + routingResult.errorCode());
        }

        // Extract routing data
        Map<String, Object> routingData = routingResult.data();
        if (routingData == null) {
            return new PrepareResult(false, null, null, null, null,
                    "No routing data returned");
        }

        String capabilityId = (String) routingData.get("capabilityId");
        String capabilityVersion = (String) routingData.get("capabilityVersion");
        @SuppressWarnings("unchecked")
        Map<String, Object> modelArguments = (Map<String, Object>) routingData.get("modelArguments");
        long snapshotVersion = routingResult.snapshotVersion();

        if (capabilityId == null || capabilityVersion == null || modelArguments == null) {
            return new PrepareResult(false, null, null, null, null,
                    "Incomplete routing data for Prepare");
        }

        // Load the manifest for parameter binding
        var manifestOpt = catalogPort.findCapability(capabilityId, capabilityVersion);
        if (manifestOpt.isEmpty()) {
            return new PrepareResult(false, null, null, null, null,
                    "Capability manifest not found: " + capabilityId);
        }
        CapabilityManifest manifest = manifestOpt.get();

        // Step 2: Validate and bind complete parameters
        Principal principal = authenticationPort.authenticate(requestContext);
        com.ai.gateway.domain.model.SystemContext systemContext =
                new com.ai.gateway.domain.model.SystemContext(
                        UUID.randomUUID().toString(),
                        Instant.now().toEpochMilli() + 30000,
                        null, locale);

        java.util.List<Object> boundArguments;
        try {
            ArgumentBinder binder = new ArgumentBinder(
                    typeConverterRegistry, schemaValidator,
                    principal, systemContext, manifest);
            boundArguments = binder.bind(modelArguments);
        } catch (IllegalArgumentException e) {
            log.warn("Parameter binding failed: {}", e.getMessage());
            return new PrepareResult(false, null, null, null, null,
                    "Parameter binding failed: " + e.getMessage());
        }

        // Step 3: Execute authorization and optional read-only pre-check
        boolean authorized = authorizationPort.authorizeExecution(
                principal, capabilityId, capabilityVersion);
        if (!authorized) {
            log.warn("Authorization denied for write operation: {}", capabilityId);
            return new PrepareResult(false, null, null, null, null,
                    "Authorization denied");
        }

        // Step 4: Generate user-understandable redacted summary
        String redactedSummary = generateRedactedSummary(manifest, modelArguments);

        // Step 5: Bind the client idempotency key to the authenticated request,
        // selected capability and frozen argument digest. The resulting value
        // is stable across retries and remains bounded for the database index.
        String operationId = UUID.randomUUID().toString();
        String serializedArguments = argumentPayloadCodec.encode(boundArguments);
        String argumentsDigest = computeDigest(serializedArguments);
        String principalDigest = computeDigest(principal.subject());
        String idempotencyKey = buildIdempotencyKey(clientIdempotencyKey, principalDigest,
                capabilityId, capabilityVersion, argumentsDigest);

        // Encrypt the arguments at rest
        String encryptedArguments = encryptionPort.encrypt(serializedArguments);

        // Generate manifest digest
        String manifestDigest = ManifestDigest.sha256(manifest);

        // Step 6: Persist immutable operation record
        Instant expiresAt = Instant.now().plusSeconds(CONFIRMATION_TOKEN_TTL_SECONDS);
        ConfirmationSummary summary = new ConfirmationSummary(
                capabilityId, capabilityVersion, manifest.spec().risk(),
                manifest.spec().invocation().interfaceName(),
                manifest.spec().invocation().method(),
                manifest.spec().invocation().serialization(),
                java.util.List.of(), java.util.List.of(),
                manifest.spec().output().projections(),
                manifest.spec().output().redactions(),
                manifest.spec().authorization() != null
                        ? manifest.spec().authorization().permissions()
                        : java.util.List.of(),
                "PREPARED", manifestDigest);

        OperationRecord record = new OperationRecord(
                operationId,
                OperationState.PREPARED,
                principalDigest,
                principal.orgId(),
                capabilityId,
                capabilityVersion,
                manifestDigest,
                snapshotVersion,
                encryptedArguments,
                argumentsDigest,
                idempotencyKey,
                "policy-decision-" + operationId,
                summary,
                expiresAt,
                0L // initial optimistic concurrency version
        );

        OperationRecord persisted = operationRepository.saveOrGetByIdempotencyKey(record);
        log.info("Operation record persisted: operationId={}, capability={}, replay={}",
                persisted.operationId(), capabilityId, !persisted.operationId().equals(operationId));

        // Step 7: Return short-lived confirmation token and expiry time
        ConfirmationToken token = confirmationTokenCodec.issue(
                persisted.operationId(), persisted.principalDigest(), persisted.orgId(),
                persisted.argumentsDigest(), persisted.expiresAt());

        log.info("Prepare phase complete: operationId={}, expiresAt={}",
                persisted.operationId(), persisted.expiresAt());

        return new PrepareResult(true, persisted.operationId(), token, redactedSummary,
                persisted.expiresAt(), null);
    }

    /**
     * Generates a user-understandable redacted summary of the operation.
     *
     * @param manifest the capability manifest
     * @param modelArguments the model-generated arguments
     * @return a redacted summary string
     */
    private String generateRedactedSummary(CapabilityManifest manifest,
                                            Map<String, Object> modelArguments) {
        StringBuilder sb = new StringBuilder();
        sb.append("Operation: ").append(manifest.spec().displayName());
        sb.append(" (").append(manifest.metadata().id()).append(")");
        sb.append("\nDescription: ").append(manifest.spec().description());
        sb.append("\nParameters:");
        for (Map.Entry<String, Object> entry : modelArguments.entrySet()) {
            String value = String.valueOf(entry.getValue());
            // Redact sensitive values — show field name and length only
            String redacted = value.length() > 4
                    ? value.substring(0, 2) + "***" + value.substring(value.length() - 2)
                    : "***";
            sb.append("\n ").append(entry.getKey()).append(": ").append(redacted);
        }
        return sb.toString();
    }

    /**
     * Computes a SHA-256 digest of the given content.
     *
     * @param content the content to digest
     * @return the hex-encoded digest
     */
    private String computeDigest(String content) {
        return Sha256Digest.sha256Hex(content);
    }

    static String buildIdempotencyKey(String clientKey, String principalDigest,
                                      String capabilityId, String capabilityVersion,
                                      String argumentsDigest) {
        return Sha256Digest.sha256Hex(String.join("\n", clientKey, principalDigest,
                capabilityId, capabilityVersion, argumentsDigest));
    }

    private static void validateClientIdempotencyKey(String clientKey) {
        if (clientKey == null || clientKey.isBlank() || clientKey.length() > 128
                || clientKey.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    "Idempotency-Key is required, must be <= 128 characters, and contain no control characters");
        }
    }

    /**
     * The result of a Prepare operation.
     *
     * @param success whether the prepare succeeded
     * @param operationId the unique operation identifier
     * @param token the short-lived confirmation token
     * @param summary the user-understandable redacted summary
     * @param expiresAt the token expiry time
     * @param error the error message; null on success
     */
    public record PrepareResult(
            boolean success,
            String operationId,
            ConfirmationToken token,
            String summary,
            Instant expiresAt,
            String error
    ) {
    }
}
