package com.ai.gateway.adapter.dubbo;

import com.ai.gateway.domain.model.AttachmentWhitelist;
import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.DeadlineBudget;
import com.ai.gateway.domain.model.ErrorCode;
import com.ai.gateway.domain.model.InvocationRequest;
import com.ai.gateway.domain.model.InvocationResult;
import com.ai.gateway.domain.model.Protocol;
import com.ai.gateway.domain.model.ProtocolBinding;
import com.ai.gateway.domain.model.ValidationReport;
import com.ai.gateway.domain.port.InvocationAdapter;
import com.ai.gateway.domain.port.ManifestRepository;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.service.GenericService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Dubbo generic invocation adapter implementing {@link InvocationAdapter}
 *
 * <p>This adapter uses Apache Dubbo's {@link GenericService} to invoke
 * target capabilities without loading any business API JAR at runtime.
 * The method name and parameter type names come from the published
 * Manifest's {@link ProtocolBinding}. The adapter does NOT call
 * {@code Class.forName} — all type information exists as strings only.</p>
 *
 * <p>The invocation flow is:</p>
 * <ol>
 * <li>Look up the published Manifest by capabilityId + version to obtain
 * the {@link ProtocolBinding}.</li>
 * <li>Get or create a {@link GenericService} from
 * {@link DubboReferenceManager}.</li>
 * <li>Build generic arguments using {@link GenericArgumentBuilder}.</li>
 * <li>Build Dubbo attachments using {@link DubboAttachmentManager}.</li>
 * <li>Set attachments on {@link RpcContext} and call
 * {@code genericService.$invoke(method, parameterTypes, arguments)}.</li>
 * <li>Strip protocol metadata keys from the result using
 * {@link GenericResultStripper}.</li>
 * <li>Return an {@link InvocationResult} with JSON-compatible data.</li>
 * </ol>
 *
 * <p>The adapter must not perform natural-language routing, user
 * authorization, or capability state changes.</p>
 *
 * @since 0.1.0
 */
@Component
public class DubboInvocationAdapter implements InvocationAdapter {

    private static final Logger log = LoggerFactory.getLogger(DubboInvocationAdapter.class);

    private final DubboReferenceManager referenceManager;
    private final GenericArgumentBuilder argumentBuilder;
    private final GenericResultStripper resultStripper;
    private final DubboAttachmentManager attachmentManager;
    private final ManifestRepository manifestRepository;

    /**
     * Constructs a new DubboInvocationAdapter.
     *
     * @param referenceManager the Dubbo reference cache manager
     * @param argumentBuilder the generic argument builder
     * @param resultStripper the protocol metadata stripper
     * @param attachmentManager the attachment whitelist manager
     * @param manifestRepository the manifest repository for looking up
     * published manifests
     */
    public DubboInvocationAdapter(DubboReferenceManager referenceManager,
                                  GenericArgumentBuilder argumentBuilder,
                                  GenericResultStripper resultStripper,
                                  DubboAttachmentManager attachmentManager,
                                  ManifestRepository manifestRepository) {
        this.referenceManager = Objects.requireNonNull(referenceManager,
                "referenceManager must not be null");
        this.argumentBuilder = Objects.requireNonNull(argumentBuilder,
                "argumentBuilder must not be null");
        this.resultStripper = Objects.requireNonNull(resultStripper,
                "resultStripper must not be null");
        this.attachmentManager = Objects.requireNonNull(attachmentManager,
                "attachmentManager must not be null");
        this.manifestRepository = Objects.requireNonNull(manifestRepository,
                "manifestRepository must not be null");
        log.info("DubboInvocationAdapter initialized");
    }

    @Override
    public Protocol protocol() {
        return Protocol.DUBBO;
    }

    /**
     * Validates the Dubbo protocol binding for structural, semantic, and
     * security compliance.
     *
     * <p>Validation includes:</p>
     * <ul>
     * <li>Protocol is {@link Protocol#DUBBO}.</li>
     * <li>{@code registryRef} is present (references a pre-configured
     * registry).</li>
     * <li>{@code serialization} belongs to the platform whitelist
     *.</li>
     * <li>{@code parameterTypes} correspond one-to-one with argument
     * positions.</li>
     * <li>{@code group} and {@code version} are present for service
     * resolution.</li>
     * </ul>
     *
     * @param binding the protocol binding to validate
     * @return the validation report; valid only if errors is empty
     */
    @Override
    public ValidationReport validate(ProtocolBinding binding) {
        Objects.requireNonNull(binding, "binding must not be null");

        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // Protocol must be DUBBO
        if (binding.protocol() != Protocol.DUBBO) {
            errors.add("Protocol must be DUBBO, got: " + binding.protocol());
        }

        // registryRef must be present
        if (binding.registryRef() == null || binding.registryRef().isBlank()) {
            errors.add("registryRef must not be null or blank");
        }

        // serialization must be in whitelist
        if (binding.serialization() == null) {
            errors.add("serialization must not be null");
        } else if (!SerializationWhitelist.isAllowed(binding.serialization())) {
            errors.add("Serialization '" + binding.serialization()
                    + "' is not in the platform whitelist. Allowed: "
                    + SerializationWhitelist.allowedValues()
                    + "");
        }

        // group and version should be present for service resolution
        if (binding.group() == null || binding.group().isBlank()) {
            warnings.add("group is null or blank — Dubbo default group will be used");
        }
        if (binding.version() == null || binding.version().isBlank()) {
            warnings.add("version is null or blank — Dubbo default version will be used");
        }

        // parameterTypes must correspond one-to-one with arguments
        if (binding.parameterTypes().size() != binding.arguments().size()) {
            errors.add("parameterTypes count (" + binding.parameterTypes().size()
                    + ") does not match arguments count (" + binding.arguments().size()
                    + ") — must correspond one-to-one");
        }

        if (errors.isEmpty()) {
            log.debug("Dubbo binding validation passed: interface={}, method={}",
                    binding.interfaceName(), binding.method());
            return ValidationReport.success();
        } else {
            log.warn("Dubbo binding validation failed: {} error(s)", errors.size());
            return new ValidationReport(false, errors, warnings);
        }
    }

    /**
     * Invokes the target capability using Dubbo generic invocation.
     *
     * <p>The adapter looks up the published Manifest to obtain the
     * {@link ProtocolBinding}, builds generic arguments, sets attachments,
     * calls {@code genericService.$invoke}, strips protocol metadata from
     * the result, and returns a JSON-compatible {@link InvocationResult}.</p>
     *
     * @param request the protocol-neutral invocation request
     * @return the protocol-neutral invocation result; never null
     */
    @Override
    public InvocationResult invoke(InvocationRequest request) {
        Objects.requireNonNull(request, "request must not be null");

        long startTime = System.currentTimeMillis();
        String capabilityId = request.capabilityId();
        String capabilityVersion = request.capabilityVersion();

        log.debug("Dubbo invocation starting: capability={}, version={}",
                capabilityId, capabilityVersion);

        // Step 1: Look up the published Manifest to obtain the ProtocolBinding
        Optional<CapabilityManifest> manifestOpt =
                manifestRepository.findByIdAndVersion(capabilityId, capabilityVersion);
        if (manifestOpt.isEmpty()) {
            log.error("Published manifest not found: capability={}, version={}",
                    capabilityId, capabilityVersion);
            return new InvocationResult(
                    null,
                    "ERROR",
                    ErrorCode.CAPABILITY_UNAVAILABLE,
                    "Published manifest not found for capability: "
                            + capabilityId + " version: " + capabilityVersion,
                    Map.of("durationMs", String.valueOf(
                            System.currentTimeMillis() - startTime))
            );
        }

        CapabilityManifest manifest = manifestOpt.get();
        ProtocolBinding binding = manifest.spec().invocation();

        // Step 2: Validate the binding
        ValidationReport validation = validate(binding);
        if (!validation.valid()) {
            log.error("Protocol binding validation failed: {}", validation.errors());
            return new InvocationResult(
                    null,
                    "ERROR",
                    ErrorCode.PROTOCOL_ERROR,
                    "Protocol binding validation failed: " + validation.errors(),
                    Map.of("durationMs", String.valueOf(
                            System.currentTimeMillis() - startTime))
            );
        }

        // Step 3: Get GenericService from DubboReferenceManager
        GenericService genericService;
        try {
            genericService = referenceManager.getOrCreate(
                    binding.registryRef(),
                    binding.interfaceName(),
                    binding.group(),
                    binding.version(),
                    binding.serialization());
        } catch (Exception e) {
            log.error("Failed to get GenericService: {}", e.getMessage());
            return new InvocationResult(
                    null,
                    "ERROR",
                    ErrorCode.PROTOCOL_ERROR,
                    "Failed to obtain Dubbo reference: " + e.getMessage(),
                    Map.of("durationMs", String.valueOf(
                            System.currentTimeMillis() - startTime),
                            "interface", binding.interfaceName())
            );
        }

        // Step 4: Build generic arguments using GenericArgumentBuilder
        Object[] argumentValues;
        try {
            argumentValues = argumentBuilder.buildArguments(
                    request.boundArguments(), binding.parameterTypes());
        } catch (Exception e) {
            log.error("Failed to build generic arguments: {}", e.getMessage());
            return new InvocationResult(
                    null,
                    "ERROR",
                    ErrorCode.ARGUMENT_VALIDATION_FAILED,
                    "Failed to build generic arguments: " + e.getMessage(),
                    Map.of("durationMs", String.valueOf(
                            System.currentTimeMillis() - startTime))
            );
        }

        // Step 5: Build attachments and set on RpcContext
        Map<String, String> attachments = attachmentManager.buildAttachments(
                request.systemContext(), new AttachmentWhitelist());

        // Apply deadline from the request's deadline budget
        DeadlineBudget deadlineBudget = request.deadlineBudget();
        if (!deadlineBudget.isExpired()) {
            attachments.putIfAbsent("deadline",
                    String.valueOf(System.currentTimeMillis() + deadlineBudget.remainingMs()));
        }

        // Set attachments on RpcContext before invocation
        try {
            RpcContext.getClientAttachment().setAttachments(attachments);
        } catch (Exception e) {
            log.warn("Failed to set Dubbo attachments: {}", e.getMessage());
            // Continue — attachments are best-effort context propagation
        }

        // Step 6: Call genericService.$invoke
        referenceManager.incrementInFlight();
        Object rawResult;
        try {
            String[] parameterTypeNames = binding.parameterTypes().toArray(new String[0]);
            rawResult = genericService.$invoke(
                    binding.method(),
                    parameterTypeNames,
                    argumentValues);
        } catch (org.apache.dubbo.rpc.RpcException e) {
            log.error("Dubbo RpcException: code={}, message={}",
                    e.getCode(), e.getMessage());
            ErrorCode errorCode = classifyRpcException(e);
            return new InvocationResult(
                    null,
                    "RPC_ERROR",
                    errorCode,
                    "Dubbo RPC error: " + e.getMessage(),
                    Map.of("durationMs", String.valueOf(
                            System.currentTimeMillis() - startTime),
                            "method", binding.method(),
                            "rpcCode", String.valueOf(e.getCode()))
            );
        } catch (Exception e) {
            log.error("Dubbo invocation failed: {}", e.getMessage());
            return new InvocationResult(
                    null,
                    "ERROR",
                    ErrorCode.PROVIDER_REJECTED,
                    "Dubbo invocation failed: " + e.getMessage(),
                    Map.of("durationMs", String.valueOf(
                            System.currentTimeMillis() - startTime),
                            "method", binding.method())
            );
        } finally {
            referenceManager.decrementInFlight();
            // Clear RpcContext attachments after invocation
            try {
                RpcContext.getClientAttachment().clearAttachments();
            } catch (Exception e) {
                log.debug("Failed to clear RpcContext attachments", e);
            }
        }

        long durationMs = System.currentTimeMillis() - startTime;

        // Step 7: Strip protocol metadata keys from result
        // Must be called BEFORE Envelope judgment, projection, and Schema validation
        Object strippedResult = resultStripper.strip(rawResult);

        log.info("Dubbo invocation completed: capability={}, method={}, durationMs={}",
                capabilityId, binding.method(), durationMs);

        // Step 8: Return InvocationResult with JSON-compatible data
        return new InvocationResult(
                strippedResult,
                "OK",
                null,
                null,
                Map.of(
                        "durationMs", String.valueOf(durationMs),
                        "interface", binding.interfaceName(),
                        "method", binding.method()
                )
        );
    }

    /**
     * Classifies a Dubbo RpcException into the appropriate ErrorCode.
     *
     * <p>Dubbo RpcException codes:</p>
     * <ul>
     * <li>1 (TIMEOUT_EXCEPTION) → PROVIDER_TIMEOUT</li>
     * <li>2 (NETWORK_EXCEPTION) → PROVIDER_TIMEOUT (retryable)</li>
     * <li>3 (FORBIDDEN_EXCEPTION) → PERMISSION_DENIED</li>
     * <li>BIZ_EXCEPTION → PROVIDER_REJECTED</li>
     * <li>5 (METHOD_NOT_FOUND) → PROTOCOL_ERROR</li>
     * <li>others → PROTOCOL_ERROR</li>
     * </ul>
     *
     * @param e the Dubbo RpcException
     * @return the appropriate ErrorCode
     */
    private ErrorCode classifyRpcException(RpcException e) {
        return switch (e.getCode()) {
            case RpcException.TIMEOUT_EXCEPTION,
                 RpcException.NETWORK_EXCEPTION ->
                    ErrorCode.PROVIDER_TIMEOUT;
            case RpcException.FORBIDDEN_EXCEPTION ->
                    ErrorCode.PERMISSION_DENIED;
            case RpcException.BIZ_EXCEPTION ->
                    ErrorCode.PROVIDER_REJECTED;
            default -> {
                log.warn("Unclassified Dubbo RpcException code: {}", e.getCode());
                yield ErrorCode.PROTOCOL_ERROR;
            }
        };
    }
}
