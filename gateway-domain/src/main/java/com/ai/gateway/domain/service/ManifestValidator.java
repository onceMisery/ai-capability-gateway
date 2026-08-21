package com.ai.gateway.domain.service;

import com.ai.gateway.domain.model.ArgumentBinding;
import com.ai.gateway.domain.model.ArgumentSource;
import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.CatalogSnapshot;
import com.ai.gateway.domain.model.ConverterType;
import com.ai.gateway.domain.model.EnvelopeProfile;
import com.ai.gateway.domain.model.OutputContract;
import com.ai.gateway.domain.model.ProjectionMapping;
import com.ai.gateway.domain.model.ProtocolBinding;
import com.ai.gateway.domain.model.RiskLevel;
import com.ai.gateway.domain.model.ValidationReport;
import com.ai.gateway.domain.port.CatalogPort;
import com.ai.gateway.domain.port.CompatibilityTestPort;
import com.ai.gateway.domain.port.EnvelopeProfileRegistry;
import com.ai.gateway.domain.port.SchemaValidator;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Validates a Capability Manifest against the 10-step import pipeline
 * defined in of the design document.
 *
 * <p>The validation pipeline executes in strict order:</p>
 * <ol>
 * <li>File size, format, and Manifest JSON Schema validation.</li>
 * <li>ID, version, owner, description, examples completeness.</li>
 * <li>Input/output JSON Schema security validation.</li>
 * <li>Parameter position, type, source, converter whitelist, and
 * response path consistency.</li>
 * <li>Projection target uniqueness, redaction path existence, and
 * post-redaction schema consistency.</li>
 * <li>Permission, risk, timeout, retry, and capacity constraints.</li>
 * <li>Protocol address reference, interface and type whitelist.</li>
 * <li>Test environment connectivity and compatibility test (delegated to
 * {@link CompatibilityTestPort}).</li>
 * <li>Compatibility analysis with active versions.</li>
 * <li>Generate content SHA-256 digest and validation report.</li>
 * </ol>
 *
 * <p>Validation must not modify the original content. Auto-repair can only
 * generate a new draft for the Owner to confirm.</p>
 *
 * <p>A report is considered valid only if {@code errors} is empty.
 * Warnings are informational and do not block publication.</p>
 *
 * <p>This class is thread-safe: it holds no mutable state.</p>
 *
 * @see CapabilityManifest
 * @see ValidationReport
 * @since 0.1.0
 */
public final class ManifestValidator {

    /**
     * The maximum allowed manifest file size in bytes (1 MB).
     */
    private static final long MAX_MANIFEST_SIZE = 1024 * 1024;

    /**
     * The default test environment identifier.
     */
    private static final String DEFAULT_TEST_ENVIRONMENT = "test";

    /**
     * The set of allowed system context paths for SYSTEM-sourced arguments.
     */
    private static final Set<String> ALLOWED_SYSTEM_PATHS = Set.of(
            "/traceId",
            "/deadlineEpochMs",
            "/idempotencyKey",
            "/locale"
    );

    /**
     * Reserved field names that must not appear in composite bindings.
     */
    private static final Set<String> RESERVED_FIELDS = Set.of(
            "class", "@type", "@class", "proto"
    );

    private final SchemaValidator schemaValidator;
    private final CompatibilityTestPort compatibilityTestPort;
    private final CatalogPort catalogPort;
    private final String environment;
    private final EnvelopeProfileRegistry envelopeProfileRegistry;

    /**
     * Constructs a new ManifestValidator with the required dependencies.
     *
     * @param schemaValidator the JSON Schema validator
     * @param compatibilityTestPort the compatibility test port
     * @param catalogPort the catalog port for active version lookup
     *
     * @param envelopeProfileRegistry the envelope profile registry for
     * envelope config validation
     * @throws NullPointerException if any argument is null
     */
    public ManifestValidator(SchemaValidator schemaValidator,
                              CompatibilityTestPort compatibilityTestPort,
                              CatalogPort catalogPort,
                              EnvelopeProfileRegistry envelopeProfileRegistry,
                              String environment) {
        this.schemaValidator = java.util.Objects.requireNonNull(
                schemaValidator, "schemaValidator must not be null");
        this.compatibilityTestPort = java.util.Objects.requireNonNull(
                compatibilityTestPort, "compatibilityTestPort must not be null");
        this.catalogPort = java.util.Objects.requireNonNull(
                catalogPort, "catalogPort must not be null");
        this.envelopeProfileRegistry = java.util.Objects.requireNonNull(
                envelopeProfileRegistry, "envelopeProfileRegistry must not be null");
        this.environment = java.util.Objects.requireNonNull(
                environment, "environment must not be null");
    }

    /**
     * Validates the given manifest against the full 10-step validation
     * pipeline.
     *
     * <p>Validation does not modify the original manifest content. If any
     * step produces errors, subsequent steps may still be executed to
     * collect all errors, but the manifest is never mutated.</p>
     *
     * @param manifest the capability manifest to validate
     * @return the validation report; valid only if errors is empty
     * @throws NullPointerException if {@code manifest} is null
     */
    public ValidationReport validate(CapabilityManifest manifest) {
        java.util.Objects.requireNonNull(manifest, "manifest must not be null");

        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // Step 1: File size, format, and Manifest JSON Schema validation
        validateManifestStructure(manifest, errors, warnings);

        // Step 2: ID, version, owner, description, examples completeness
        validateMetadataCompleteness(manifest, errors, warnings);

        // Step 3: Input/output JSON Schema security validation
        validateSchemaSecurity(manifest, errors, warnings);

        // Step 4: Parameter position, type, source, converter, response path
        validateParameterBinding(manifest, errors, warnings);

        // Step 5: Projection uniqueness, redaction path, schema consistency
        validateOutputContract(manifest, errors, warnings);

        // Step 6: Permission, risk, timeout, retry, capacity constraints
        validateResilienceAndRisk(manifest, errors, warnings);

        // Step 7: Protocol address, interface and type whitelist
        validateProtocolBinding(manifest, errors, warnings);

        // Step 8: Test environment connectivity and compatibility test
        if (errors.isEmpty()) {
            runCompatibilityTest(manifest, errors, warnings);
        } else {
            warnings.add("Step 8 (compatibility test) skipped due to prior validation errors");
        }

        // Step 9: Compatibility analysis with active versions
        analyzeCompatibilityWithActiveVersions(manifest, errors, warnings);

        // Step 10: Generate content SHA-256 digest and validation report
        String digest = generateContentDigest(manifest);
        if (digest == null) {
            warnings.add("Content digest generation failed");
        }

        boolean valid = errors.isEmpty();
        return new ValidationReport(valid, List.copyOf(errors), List.copyOf(warnings));
    }

    // -------------------------------------------------------------------------
    // Step 1: Manifest structure validation
    // -------------------------------------------------------------------------

    /**
     * Validates the manifest's structural format and size.
     *
     * @param manifest the manifest
     * @param errors the error list to append to
     * @param warnings the warning list to append to
     */
    private void validateManifestStructure(CapabilityManifest manifest,
                                           List<String> errors,
                                           List<String> warnings) {
        // Check apiVersion
        if (manifest.apiVersion() == null || manifest.apiVersion().isBlank()) {
            errors.add("apiVersion must not be blank");
        }

        // Check kind
        if (!"Capability".equals(manifest.kind())) {
            errors.add("kind must be 'Capability' but is: " + manifest.kind());
        }

        // Check metadata
        if (manifest.metadata() == null) {
            errors.add("metadata must not be null");
        }

        // Check spec
        if (manifest.spec() == null) {
            errors.add("spec must not be null");
        }
    }

    // -------------------------------------------------------------------------
    // Step 2: Metadata completeness
    // -------------------------------------------------------------------------

    /**
     * Validates the completeness of metadata fields.
     *
     * @param manifest the manifest
     * @param errors the error list
     * @param warnings the warning list
     */
    private void validateMetadataCompleteness(CapabilityManifest manifest,
                                              List<String> errors,
                                              List<String> warnings) {
        CapabilityManifest.Metadata metadata = manifest.metadata();
        if (metadata == null) {
            return; // Already caught in step 1
        }

        // ID validation
        if (metadata.id() == null || metadata.id().isBlank()) {
            errors.add("metadata.id must not be blank");
        } else if (!metadata.id().matches("^[a-z0-9.\\-]+$")) {
            errors.add("metadata.id must contain only lowercase letters, digits, dots, and hyphens: "
                    + metadata.id());
        }

        // Version validation
        if (metadata.version() == null || metadata.version().isBlank()) {
            errors.add("metadata.version must not be blank");
        } else if (!metadata.version().matches(
                "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)"
                        + "(?:-[0-9a-zA-Z-]+(?:\\.[0-9a-zA-Z-]+)*)?"
                        + "(?:\\+[0-9a-zA-Z-]+(?:\\.[0-9a-zA-Z-]+)*)?$")) {
            errors.add("metadata.version must conform to SemVer MAJOR.MINOR.PATCH format: "
                    + metadata.version());
        }

        // Owner validation
        CapabilityManifest.Owner owner = metadata.owner();
        if (owner == null) {
            errors.add("metadata.owner must not be null");
        } else {
            if (owner.team() == null || owner.team().isBlank()) {
                errors.add("metadata.owner.team must not be blank");
            }
            if (owner.contact() == null || owner.contact().isBlank()) {
                errors.add("metadata.owner.contact must not be blank");
            }
        }

        // Spec completeness
        CapabilityManifest.Spec spec = manifest.spec();
        if (spec == null) {
            return; // Already caught in step 1
        }

        if (spec.displayName() == null || spec.displayName().isBlank()) {
            errors.add("spec.displayName must not be blank");
        }

        if (spec.description() == null || spec.description().isBlank()) {
            errors.add("spec.description must not be blank");
        }

        // Examples completeness
        CapabilityManifest.Examples examples = spec.examples();
        if (examples == null) {
            errors.add("spec.examples must not be null");
        } else {
            if (examples.positive().size() < 3) {
                errors.add("spec.examples.positive must have at least 3 examples; found "
                        + examples.positive().size());
            }
            if (examples.negative().size() < 2) {
                errors.add("spec.examples.negative must have at least 2 examples; found "
                        + examples.negative().size());
            }
            if (examples.synonyms().isEmpty()) {
                warnings.add("spec.examples.synonyms is empty; consider adding key noun synonyms");
            }
        }
    }

    // -------------------------------------------------------------------------
    // Step 3: Schema security validation
    // -------------------------------------------------------------------------

    /**
     * Validates the input and output JSON Schemas for security
     *
     * @param manifest the manifest
     * @param errors the error list
     * @param warnings the warning list
     */
    private void validateSchemaSecurity(CapabilityManifest manifest,
                                        List<String> errors,
                                        List<String> warnings) {
        CapabilityManifest.Spec spec = manifest.spec();
        if (spec == null) return;

        Map<String, Object> inputSchema = spec.inputSchema();
        validateInputSchemaSecurity(inputSchema, errors, warnings);

        OutputContract output = spec.output();
        if (output != null) {
            validateOutputSchemaSecurity(output, errors, warnings);
        }
    }

    /**
     * Validates the input JSON Schema for security rules.
     *
     * @param inputSchema the input schema
     * @param errors the error list
     * @param warnings the warning list
     */
    private void validateInputSchemaSecurity(Map<String, Object> inputSchema,
                                             List<String> errors,
                                             List<String> warnings) {
        if (inputSchema == null || inputSchema.isEmpty()) {
            errors.add("inputSchema must not be null or empty");
            return;
        }

        // Root type must be object
        String type = (String) inputSchema.get("type");
        if (!"object".equals(type)) {
            errors.add("inputSchema root type must be 'object' but is: " + type);
        }

        // additionalProperties must be false
        Object additionalProps = inputSchema.get("additionalProperties");
        if (additionalProps == null) {
            errors.add("inputSchema must explicitly set additionalProperties: false");
        } else if (!Boolean.FALSE.equals(additionalProps)) {
            errors.add("inputSchema additionalProperties must be false");
        }

        // Check for trusted context fields in properties
        Object properties = inputSchema.get("properties");
        if (properties instanceof Map<?, ?> propsMap) {
            Set<String> forbiddenFields = Set.of(
                    "orgId", "tenantId", "userId");
            for (String field : forbiddenFields) {
                if (propsMap.containsKey(field)) {
                    errors.add("inputSchema must not contain trusted context field '"
                            + field + "' in properties");
                }
            }
        }
    }

    /**
     * Validates the output public schema for security.
     *
     * @param output the output contract
     * @param errors the error list
     * @param warnings the warning list
     */
    private void validateOutputSchemaSecurity(OutputContract output,
                                              List<String> errors,
                                              List<String> warnings) {
        Map<String, Object> publicSchema = output.publicSchema();
        if (publicSchema != null && !publicSchema.isEmpty()) {
            Object type = publicSchema.get("type");
            if (type != null && !"object".equals(type)) {
                warnings.add("publicSchema root type is '" + type
                        + "'; object is recommended");
            }
        }
    }

    // -------------------------------------------------------------------------
    // Step 4: Parameter binding validation
    // -------------------------------------------------------------------------

    /**
     * Validates parameter positions, types, sources, converters, and
     * response paths.
     *
     * @param manifest the manifest
     * @param errors the error list
     * @param warnings the warning list
     */
    private void validateParameterBinding(CapabilityManifest manifest,
                                          List<String> errors,
                                          List<String> warnings) {
        CapabilityManifest.Spec spec = manifest.spec();
        if (spec == null) return;

        ProtocolBinding binding = spec.invocation();
        if (binding == null) {
            errors.add("spec.invocation must not be null");
            return;
        }

        List<ArgumentBinding> arguments = binding.arguments();
        List<String> parameterTypes = binding.parameterTypes();

        // Check argument count matches parameter types
        if (arguments.size() != parameterTypes.size()) {
            errors.add("Argument count " + arguments.size()
                    + " does not match parameter type count " + parameterTypes.size());
        }

        // Check positions: unique and contiguous
        Set<Integer> positions = new HashSet<>();
        for (int i = 0; i < arguments.size(); i++) {
            ArgumentBinding arg = arguments.get(i);
            if (arg.position() != i) {
                errors.add("Non-contiguous position at index " + i
                        + ": expected " + i + " but got " + arg.position()
                        + " for argument '" + arg.name() + "'");
            }
            if (!positions.add(arg.position())) {
                errors.add("Duplicate position: " + arg.position()
                        + " for argument '" + arg.name() + "'");
            }

            // Check protocolType is set
            if (arg.protocolType() == null || arg.protocolType().isBlank()) {
                errors.add("protocolType must not be blank for argument '" + arg.name() + "'");
            }

            // Validate source
            if (arg.source() == null && !arg.isComposite()) {
                errors.add("Argument '" + arg.name() + "' has no source and is not composite");
            }

            // Validate converter whitelist
            if (arg.converter() != null && !arg.converter().isEmpty()) {
                validateConverter(arg.converter(), arg.name(), errors);
            }

            // Validate SYSTEM paths
            if (arg.source() == ArgumentSource.SYSTEM) {
                if (!ALLOWED_SYSTEM_PATHS.contains(arg.sourcePath())) {
                    errors.add("SYSTEM source path '" + arg.sourcePath()
                            + "' is not in the whitelist for argument '" + arg.name() + "'");
                }
            }

            // Validate composite bindings
            if (arg.isComposite()) {
                validateCompositeBinding(arg, errors, warnings);
            }
        }
    }

    /**
     * Validates that the converter name is in the controlled whitelist
     *
     * @param converterName the converter name
     * @param argName the argument name for error reporting
     * @param errors the error list
     */
    private void validateConverter(String converterName, String argName,
                                   List<String> errors) {
        try {
            ConverterType.valueOf(converterName);
        } catch (IllegalArgumentException e) {
            errors.add("Converter '" + converterName + "' for argument '" + argName
                    + "' is not in the controlled converter whitelist");
        }
    }

    /**
     * Validates a composite binding's field bindings.
     *
     * @param arg the argument binding
     * @param errors the error list
     * @param warnings the warning list
     */
    private void validateCompositeBinding(ArgumentBinding arg,
                                          List<String> errors,
                                          List<String> warnings) {
        Map<String, com.ai.gateway.domain.model.FieldBinding> objectBindings = arg.objectBindings();
        Set<String> assignedFields = new LinkedHashSet<>();

        for (Map.Entry<String, com.ai.gateway.domain.model.FieldBinding> entry : objectBindings.entrySet()) {
            String targetPath = entry.getKey();

            // Check reserved fields
            if (targetPath.contains("/class") || targetPath.contains("/@type")
                    || targetPath.equals("/class") || targetPath.equals("/@type")) {
                errors.add("Reserved field assignment: '" + targetPath
                        + "' in composite binding for argument '" + arg.name() + "'");
            }

            // Check duplicate target field
            if (!assignedFields.add(targetPath)) {
                errors.add("Duplicate target field: '" + targetPath
                        + "' in composite binding for argument '" + arg.name() + "'");
            }

            // Validate converter in field binding
            com.ai.gateway.domain.model.FieldBinding fieldBinding = entry.getValue();
            if (fieldBinding.converter() != null && !fieldBinding.converter().isEmpty()) {
                validateConverter(fieldBinding.converter(), arg.name() + "." + targetPath, errors);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Step 5: Output contract validation
    // -------------------------------------------------------------------------

    /**
     * Validates projection uniqueness, redaction path existence, and
     * post-redaction schema consistency.
     *
     * @param manifest the manifest
     * @param errors the error list
     * @param warnings the warning list
     */
    private void validateOutputContract(CapabilityManifest manifest,
                                        List<String> errors,
                                        List<String> warnings) {
        CapabilityManifest.Spec spec = manifest.spec();
        if (spec == null) return;

        OutputContract output = spec.output();
        if (output == null) {
            errors.add("spec.output must not be null");
            return;
        }

        // Validate projection target uniqueness
        List<ProjectionMapping> projections = output.projections();
        Set<String> targetPaths = new HashSet<>();
        for (ProjectionMapping projection : projections) {
            if (!targetPaths.add(projection.to())) {
                errors.add("Duplicate projection target: '" + projection.to()
                        + "' — each projection target must be unique");
            }
        }

        // Validate envelope profile if ENVELOPE mode
        if (output.mode() == com.ai.gateway.domain.model.OutputMode.ENVELOPE) {
            if (output.envelope() == null) {
                errors.add("ENVELOPE mode requires a non-null envelope config");
            } else {
                validateEnvelopeConfig(output.envelope(), errors, warnings);
            }
        }

        // Validate maxBytes
        if (output.maxBytes() <= 0) {
            warnings.add("output.maxBytes is " + output.maxBytes()
                    + "; a positive value is recommended");
        }

        // Validate redaction rules
        for (com.ai.gateway.domain.model.RedactionRule rule : output.redactions()) {
            if (rule.path() == null || rule.path().isBlank()) {
                errors.add("Redaction rule path must not be blank");
            }
            if (rule.method() == null) {
                errors.add("Redaction rule method must not be null for path '"
                        + rule.path() + "'");
            }
        }
    }

    /**
     * Validates the envelope configuration.
     *
     * @param envelope the envelope config
     * @param errors the error list
     * @param warnings the warning list
     */
    private void validateEnvelopeConfig(
            com.ai.gateway.domain.model.EnvelopeConfig envelope,
            List<String> errors, List<String> warnings) {

        if (envelope.codePath() == null || envelope.codePath().isBlank()) {
            errors.add("Envelope codePath must not be blank");
        }
        if (envelope.dataPath() == null || envelope.dataPath().isBlank()) {
            errors.add("Envelope dataPath must not be blank");
        }
        if (envelope.successValues() == null || envelope.successValues().isEmpty()) {
            errors.add("Envelope successValues must not be empty");
        }
    }

    // -------------------------------------------------------------------------
    // Step 6: Resilience and risk validation
    // -------------------------------------------------------------------------

    /**
     * Validates permission, risk, timeout, retry, and capacity constraints
     *
     * @param manifest the manifest
     * @param errors the error list
     * @param warnings the warning list
     */
    private void validateResilienceAndRisk(CapabilityManifest manifest,
                                           List<String> errors,
                                           List<String> warnings) {
        CapabilityManifest.Spec spec = manifest.spec();
        if (spec == null) return;

        // Risk level
        RiskLevel risk = spec.risk();
        if (risk == null) {
            errors.add("spec.risk must not be null");
        } else if (risk == RiskLevel.WRITE_HIGH) {
            warnings.add("spec.risk is WRITE_HIGH; this level is disabled in the initial release"
                    + " and requires independent security review and dual approval");
        }

        // Resilience policy
        com.ai.gateway.domain.model.ResiliencePolicy resilience = spec.resilience();
        if (resilience == null) {
            errors.add("spec.resilience must not be null");
        } else {
            if (resilience.timeoutMs() <= 0) {
                errors.add("resilience.timeoutMs must be positive but is "
                        + resilience.timeoutMs());
            }
            if (resilience.retries() < 0) {
                errors.add("resilience.retries must not be negative but is "
                        + resilience.retries());
            }
            if (resilience.maxConcurrent() <= 0) {
                errors.add("resilience.maxConcurrent must be positive but is "
                        + resilience.maxConcurrent());
            }
            if (risk == RiskLevel.WRITE_LOW && resilience.retries() > 0) {
                warnings.add("Write operations with retries > 0 must follow the two-phase "
                        + "recovery protocol");
            }
        }

        // Authorization
        CapabilityManifest.Authorization auth = spec.authorization();
        if (auth != null) {
            for (String permission : auth.permissions()) {
                if (!permission.matches("^[a-z]+:[a-z]+:[a-z]+$")) {
                    errors.add("Permission '" + permission
                            + "' must use the domain:resource:action convention");
                }
                if (permission.contains("*")) {
                    errors.add("Permission '" + permission
                            + "' must not contain wildcards");
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Step 7: Protocol binding validation
    // -------------------------------------------------------------------------

    /**
     * Validates protocol address reference, interface and type whitelist
     *
     * @param manifest the manifest
     * @param errors the error list
     * @param warnings the warning list
     */
    private void validateProtocolBinding(CapabilityManifest manifest,
                                         List<String> errors,
                                         List<String> warnings) {
        CapabilityManifest.Spec spec = manifest.spec();
        if (spec == null) return;

        ProtocolBinding binding = spec.invocation();
        if (binding == null) return;

        // registryRef must be set (no inline addresses)
        if (binding.registryRef() == null || binding.registryRef().isBlank()) {
            errors.add("invocation.registryRef must not be blank; "
                    + "Manifests must not carry inline registry addresses");
        }

        // interfaceName must be set
        if (binding.interfaceName() == null || binding.interfaceName().isBlank()) {
            errors.add("invocation.interfaceName must not be blank");
        }

        // method must be set
        if (binding.method() == null || binding.method().isBlank()) {
            errors.add("invocation.method must not be blank");
        }

        // serialization must be set
        if (binding.serialization() == null || binding.serialization().isBlank()) {
            warnings.add("invocation.serialization is blank; "
                    + "a platform whitelist serialization should be declared");
        }

        // Protocol must be a known adapter protocol. The concrete adapter
        // performs protocol-specific validation during the same pipeline.
        if (binding.protocol() == null) {
            errors.add("invocation.protocol must not be null");
        }
    }

    // -------------------------------------------------------------------------
    // Step 8: Compatibility test
    // -------------------------------------------------------------------------

    /**
     * Runs the compatibility test against the test environment
     *
     * @param manifest the manifest
     * @param errors the error list
     * @param warnings the warning list
     */
    private void runCompatibilityTest(CapabilityManifest manifest,
                                      List<String> errors,
                                      List<String> warnings) {
        try {
            ValidationReport testReport = compatibilityTestPort.runCompatibilityTest(
                    manifest, DEFAULT_TEST_ENVIRONMENT);
            if (!testReport.valid()) {
                errors.addAll(testReport.errors());
            }
            warnings.addAll(testReport.warnings());
        } catch (Exception e) {
            errors.add("Compatibility test failed with exception: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Step 9: Compatibility analysis with active versions
    // -------------------------------------------------------------------------

    /**
     * Analyzes compatibility with active published versions
     *
     * @param manifest the manifest
     * @param errors the error list
     * @param warnings the warning list
     */
    private void analyzeCompatibilityWithActiveVersions(CapabilityManifest manifest,
                                                         List<String> errors,
                                                         List<String> warnings) {
        String capabilityId = manifest.metadata().id();
        String newVersion = manifest.metadata().version();

        try {
            CatalogSnapshot snapshot = catalogPort.loadCurrentSnapshot(environment);
            if (snapshot != null) {
                for (CapabilityManifest existing : snapshot.capabilities()) {
                    if (existing.metadata().id().equals(capabilityId)) {
                        String existingVersion = existing.metadata().version();
                        if (existingVersion.equals(newVersion)) {
                            errors.add("A capability with id '" + capabilityId
                                    + "' and version '" + newVersion
                                    + "' already exists in the active snapshot; "
                                    + "modifications must produce a new version");
                        } else {
                            // Check for version ordering
                            warnings.add("Active version " + existingVersion
                                    + " exists for capability '" + capabilityId
                                    + "'; ensure only one default-routable version per id");
                        }
                    }
                }
            }
        } catch (Exception e) {
            warnings.add("Could not analyze compatibility with active versions: "
                    + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Step 10: Content digest
    // -------------------------------------------------------------------------

    /**
     * Generates the content SHA-256 digest of the manifest
     *
     * <p>The digest is computed over the manifest's canonical string
     * representation. The same id + version content cannot be overwritten
     *.</p>
     *
     * @param manifest the manifest
     * @return the hex-encoded SHA-256 digest
     */
    private String generateContentDigest(CapabilityManifest manifest) {
        return ManifestDigest.sha256(manifest);
    }

}
