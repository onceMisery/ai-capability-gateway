package com.ai.gateway.application.controlplane;

import com.ai.gateway.domain.model.CapabilityLifecycle;
import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.ValidationReport;
import com.ai.gateway.domain.port.CompatibilityTestPort;
import com.ai.gateway.domain.port.ManifestRepository;
import com.ai.gateway.domain.port.SchemaValidator;
import com.ai.gateway.domain.service.ManifestValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * Use case for importing a Capability Manifest through the 10-step validation
 * pipeline defined in of the design document.
 *
 * <p>The import pipeline executes in strict order:</p>
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
 * <li>Test environment connectivity and compatibility test.</li>
 * <li>Compatibility analysis with active versions.</li>
 * <li>Generate content SHA-256 digest and validation report.</li>
 * </ol>
 *
 * <p>If validation passes, the manifest is persisted with {@link CapabilityLifecycle#DRAFT}
 * status. The same {@code id + version} content cannot be overwritten;
 * modifications must produce a new version.</p>
 *
 * <p>This class uses constructor injection and contains no Spring annotations.
 * It is thread-safe: it holds no mutable state.</p>
 *
 * @see ManifestValidator
 * @see ManifestRepository
 * @since 0.1.0
 */
public final class ManifestImportUseCase {

    private static final Logger log = LoggerFactory.getLogger(ManifestImportUseCase.class);

    private final ManifestRepository manifestRepository;
    private final ManifestValidator manifestValidator;
    private final SchemaValidator schemaValidator;
    private final CompatibilityTestPort compatibilityTestPort;

    /**
     * Constructs a new ManifestImportUseCase with the required dependencies.
     *
     * @param manifestRepository the repository for persisting manifests
     * @param manifestValidator the domain validator implementing the 10-step pipeline
     * @param schemaValidator the JSON Schema validator for supplementary checks
     * @param compatibilityTestPort the compatibility test port for Provider probe calls
     * @throws NullPointerException if any argument is null
     */
    public ManifestImportUseCase(ManifestRepository manifestRepository,
                                  ManifestValidator manifestValidator,
                                  SchemaValidator schemaValidator,
                                  CompatibilityTestPort compatibilityTestPort) {
        this.manifestRepository = java.util.Objects.requireNonNull(
                manifestRepository, "manifestRepository must not be null");
        this.manifestValidator = java.util.Objects.requireNonNull(
                manifestValidator, "manifestValidator must not be null");
        this.schemaValidator = java.util.Objects.requireNonNull(
                schemaValidator, "schemaValidator must not be null");
        this.compatibilityTestPort = java.util.Objects.requireNonNull(
                compatibilityTestPort, "compatibilityTestPort must not be null");
    }

    /**
     * Imports a Capability Manifest through the full 10-step validation
     * pipeline.
     *
     * <p>If validation passes, the manifest is saved with {@code DRAFT}
     * status and a content SHA-256 digest. If validation fails, the manifest
     * is not persisted and the validation report is returned with the errors.</p>
     *
     * @param manifest the capability manifest to import
     * @return the import result containing the validation report and manifest digest
     * @throws NullPointerException if {@code manifest} is null
     */
    public ImportResult importManifest(CapabilityManifest manifest) {
        java.util.Objects.requireNonNull(manifest, "manifest must not be null");
        log.info("Importing manifest: id={}, version={}",
                manifest.metadata().id(), manifest.metadata().version());

        // Step 1-10: Run the full validation pipeline via the domain validator
        ValidationReport report = manifestValidator.validate(manifest);

        if (!report.valid()) {
            log.warn("Manifest validation failed for id={}, version={}: {} errors",
                    manifest.metadata().id(), manifest.metadata().version(),
                    report.errors().size());
            return new ImportResult(false, report, null,
                    "Validation failed: " + String.join("; ", report.errors()));
        }

        // Generate the content SHA-256 digest
        String digest = generateContentDigest(manifest);
        if (digest == null) {
            log.error("Failed to generate content digest for manifest id={}, version={}",
                    manifest.metadata().id(), manifest.metadata().version());
            return new ImportResult(false, report, null,
                    "Failed to generate content SHA-256 digest");
        }

        // Check for duplicate id+version
        if (manifestRepository.findByIdAndVersion(
                manifest.metadata().id(), manifest.metadata().version()).isPresent()) {
            log.warn("Manifest with id={} and version={} already exists; cannot overwrite",
                    manifest.metadata().id(), manifest.metadata().version());
            return new ImportResult(false, report, digest,
                    "A manifest with id '" + manifest.metadata().id()
                            + "' and version '" + manifest.metadata().version()
                            + "' already exists; modifications must produce a new version");
        }

        // Persist the manifest with DRAFT status and its content digest
        manifestRepository.save(manifest, digest);

        log.info("Manifest imported successfully: id={}, version={}, digest={}",
                manifest.metadata().id(), manifest.metadata().version(), digest);

        return new ImportResult(true, report, digest, null);
    }

    /**
     * Generates the content SHA-256 digest of the manifest.
     *
     * <p>The digest is computed over the manifest's canonical string
     * representation. The same {@code id + version} content cannot be
     * overwritten.</p>
     *
     * @param manifest the capability manifest
     * @return the hex-encoded SHA-256 digest, or {@code null} if generation fails
     */
    private String generateContentDigest(CapabilityManifest manifest) {
        try {
            String content = manifestToCanonicalString(manifest);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(content.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(digest);
        } catch (NoSuchAlgorithmException e) {
            log.error("SHA-256 algorithm not available", e);
            return null;
        }
    }

    /**
     * Converts the manifest to a canonical string representation for digest
     * computation.
     *
     * @param manifest the manifest
     * @return the canonical string
     */
    private String manifestToCanonicalString(CapabilityManifest manifest) {
        StringBuilder sb = new StringBuilder();
        sb.append(manifest.apiVersion()).append('\n');
        sb.append(manifest.kind()).append('\n');

        CapabilityManifest.Metadata meta = manifest.metadata();
        sb.append(meta.id()).append('\n');
        sb.append(meta.version()).append('\n');
        if (meta.owner() != null) {
            sb.append(meta.owner().team()).append('\n');
            sb.append(meta.owner().contact()).append('\n');
        }
        if (meta.tags() != null) {
            sb.append(String.join(",", meta.tags())).append('\n');
        }

        CapabilityManifest.Spec spec = manifest.spec();
        sb.append(spec.displayName()).append('\n');
        sb.append(spec.description()).append('\n');
        sb.append(spec.risk()).append('\n');

        return sb.toString();
    }

    /**
     * Converts a byte array to a lowercase hex string.
     *
     * @param bytes the bytes
     * @return the hex string
     */
    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /**
     * The result of a manifest import operation.
     *
     * @param success whether the import succeeded
     * @param report the validation report from the 10-step pipeline
     * @param manifestDigest the content SHA-256 digest; null on failure
     * @param error the error message; null on success
     */
    public record ImportResult(
            boolean success,
            ValidationReport report,
            String manifestDigest,
            String error
    ) {
    }
}
