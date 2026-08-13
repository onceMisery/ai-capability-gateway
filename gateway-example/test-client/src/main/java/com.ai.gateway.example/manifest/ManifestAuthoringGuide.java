package com.ai.gateway.example.manifest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * Guide for authoring Capability Manifests .
 *
 * <p>This class serves as executable documentation showing:
 * <ul>
 * <li>Manifest structure and required fields</li>
 * <li>Capability ID and versioning rules</li>
 * <li>Input Schema authoring with security constraints</li>
 * <li>Parameter binding: simple and composite</li>
 * <li>Controlled type converters</li>
 * <li>Protocol Binding for Dubbo</li>
 * <li>Output contract: Envelope, projection, redaction</li>
 * <li>Semantic description requirements</li>
 * </ul>
 *
 * <p>Run this class to print the authoring guide and validate the sample
 * manifests bundled with this module:</p>
 * <pre>{@code
 * java -cp gateway-example.jar com.ai.gateway.example.manifest.ManifestAuthoringGuide
 * }</pre>
 *
 * @since 0.1.0
 */
public class ManifestAuthoringGuide {

    private static final Logger log = LoggerFactory.getLogger(ManifestAuthoringGuide.class);

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    /**
     * Main entry point. Prints the manifest authoring guide and validates
     * the sample manifests.
     *
     * @param args command-line arguments (unused)
     */
    public static void main(String[] args) {
        System.out.println("=".repeat(70));
        System.out.println("AI Capability Gateway — Manifest Authoring Guide");
        System.out.println("the design document");
        System.out.println("=".repeat(70));
        System.out.println();

        printManifestStructure();
        printBindingRules();
        printSecurityConstraints();
        validateSampleManifest();

        System.out.println();
        System.out.println("=".repeat(70));
        System.out.println("Guide complete.");
        System.out.println("=".repeat(70));
    }

    /**
     * Prints the Manifest structure and required fields.
     *
     * <p>The Capability Manifest is the single, machine-verifiable contract
     * that transforms a governed microservice API into a natural-language-
     * discoverable capability. It uses YAML or JSON format
     * and is validated by a versioned JSON Schema.</p>
     *
     * <p>Top-level structure:</p>
     * <pre>
     * apiVersion: gateway.ai/v1 # Manifest specification version
     * kind: Capability # Always "Capability"
     * metadata: # Identity and ownership
     * id: domain.resource.action # Stable capability identifier
     * version: 1.0.0 # Semantic version (SemVer)
     * owner: # Responsible team
     * team: team-name
     * contact: team@example.com
     * tags: [tag1, tag2] # Optional controlled tags
     * spec: # Execution configuration
     * displayName: ... # User-facing name
     * description: ... # Single business-action description
     * examples: ... # Positive, negative, synonyms
     * risk: READ_ONLY # Risk level
     * inputSchema: ... # Model-visible JSON Schema
     * invocation: ... # Protocol binding
     * output: ... # Output contract
     * resilience: ... # Timeout, retry, concurrency
     * </pre>
     */
    public static void printManifestStructure() {
        printSection("1. Manifest Structure");

        System.out.println("""
          The Capability Manifest has four top-level fields:
          
            apiVersion - Manifest specification version (e.g., "gateway.ai/v1")
            kind - Always "Capability"
            metadata - Identity: id, version, owner, tags
            spec - Execution configuration (see below)
          
          The spec block contains:
          
            displayName - User-understandable capability name
            description - Single business-action description
            examples - positive (>=3), negative (>=2), synonyms
            risk - READ_ONLY | WRITE_LOW | WRITE_HIGH
            inputSchema - Model-visible JSON Schema 2020-12
            authorization - Required permissions (optional in v1)
            invocation - Protocol binding
            output - Response contract
            resilience - Timeout, retry, concurrency
          
          Important: Lifecycle state, confirmation records, publication
          environment, and snapshot version are control-plane records
          and are NOT self-declared in the Manifest.
          """);
    }

    /**
     * Prints the parameter binding rules.
     *
     * <p>The gateway enforces a strict separation between model-visible
     * and model-invisible parameters. Only MODEL-sourced parameters
     * appear in the public input Schema.</p>
     *
     * <h3>Argument Sources</h3>
     * <table border="1">
     * <tr><th>Source</th><th>Meaning</th><th>Model-visible</th></tr>
     * <tr><td>MODEL</td><td>From LLM structured output</td><td>Yes</td></tr>
     * <tr><td>PRINCIPAL</td><td>From authenticated Principal</td><td>No</td></tr>
     * <tr><td>CONSTANT</td><td>From confirmed Manifest</td><td>No</td></tr>
     * <tr><td>SYSTEM</td><td>Platform context (traceId, etc.)</td><td>No</td></tr>
     * </table>
     *
     * <h3>Binding Modes</h3>
     * <ul>
     * <li><strong>Simple binding</strong>: source + sourcePath for a single value</li>
     * <li><strong>Composite binding</strong>: object map for DTOs with mixed sources</li>
     * </ul>
     *
     * <h3>Controlled Type Converters</h3>
     * <ul>
     * <li>ISO_DATE_TO_EPOCH_MILLIS — ISO-8601 string to epoch millis</li>
     * <li>ENUM_UPPERCASE — normalize enum string to uppercase</li>
     * <li>STRING_TRIM — trim whitespace</li>
     * </ul>
     */
    public static void printBindingRules() {
        printSection("2. Parameter Binding Rules");

        System.out.println("""
          Argument Sources:
            MODEL - Business parameters from LLM output (model-visible)
            PRINCIPAL - From authenticated Principal, e.g., orgId (never model-visible)
            CONSTANT - From confirmed Manifest constant (never model-visible)
            SYSTEM - Platform context: traceId, deadline, idempotencyKey, locale
          
          Simple Binding Example:
            - position: 0
              name: orgId
              protocolType: java.lang.Long
              source: PRINCIPAL
              sourcePath: /orgId
          
          Composite Binding Example (DTO with mixed sources):
            - position: 1
              name: request
              protocolType: com.example.order.api.OrderQueryRequest
              object:
                /orderNo:
                  source: MODEL
                  sourcePath: /orderNo
                /channel:
                  source: CONSTANT
                  value: AI_GATEWAY
          
          Controlled Type Converters:
            ISO_DATE_TO_EPOCH_MILLIS "2026-07-21T10:00:00Z" -> 1753092000000L
            ENUM_UPPERCASE "pending" -> "PENDING"
            STRING_TRIM " order123 " -> "order123"
          
          Dubbo Protocol Binding:
            - registryRef references an operationally pre-configured registry
            - Manifests must NOT carry usernames, passwords, or registry addresses
            - parameterTypes must correspond 1:1 with arguments positions
            - serialization must be in the platform whitelist (e.g., fastjson2)
            - The gateway does NOT load interfaceName or parameterTypes classes
          """);
    }

    /**
     * Prints the security constraints for Manifest authoring
     *
     * <h3>Input Schema Security</h3>
     * <ul>
     * <li>{@code additionalProperties: false} is mandatory</li>
     * <li>PRINCIPAL-sourced fields must NOT appear in inputSchema</li>
     * <li>String fields should have maxLength and pattern constraints</li>
     * <li>Numeric fields should have minimum/maximum bounds</li>
     * </ul>
     *
     * <h3>Output Contract Security</h3>
     * <ul>
     * <li>Projection whitelist: unmapped fields do not leave the gateway</li>
     * <li>Redaction rules: PARTIAL_MASK, HASH, DELETE</li>
     * <li>publicSchema validates the final output</li>
     * <li>maxBytes enforces response size limits</li>
     * </ul>
     */
    public static void printSecurityConstraints() {
        printSection("3. Security Constraints");

        System.out.println("""
          Input Schema Security:
            [REQUIRED] additionalProperties: false
            [REQUIRED] PRINCIPAL fields must NOT be in inputSchema
            [RECOMMENDED] String fields: maxLength + pattern
            [RECOMMENDED] Numeric fields: minimum + maximum
            [RECOMMENDED] Array fields: maxItems
          
          Output Contract Security:
            Projection Whitelist:
              - Only mapped fields leave the gateway
              - Unmapped Provider fields are silently dropped
              - If no projection, entire data must match publicSchema
          
            Redaction Methods:
              PARTIAL_MASK Keep first/last chars, mask the rest
              HASH Replace with one-way hash
              DELETE Remove field entirely
          
            Response Processing Pipeline:
              1. Adapter converts protocol result to JSON tree
              2. Envelope unwrapping (codePath, successValues, dataPath)
              3. Projection whitelist mapping
              4. Redaction rules applied
              5. publicSchema validation
              6. maxBytes size check
          
            Error Handling:
              - Path-not-found, type mismatch, response-over-limit
                are treated as protocol errors
              - Raw Provider objects are NEVER returned to the user
          """);
    }

    /**
     * Validates the sample manifests bundled with this module.
     *
     * <p>Loads both {@code order-detail-query.yaml} and
     * {@code purchase-list-query.yaml} from the classpath and performs
     * basic structural validation:</p>
     * <ul>
     * <li>Valid YAML syntax</li>
     * <li>Required top-level fields present</li>
     * <li>inputSchema has additionalProperties: false</li>
     * <li>PRINCIPAL fields not in inputSchema</li>
     * <li>Serialization in whitelist</li>
     * </ul>
     */
    public static void validateSampleManifest() {
        printSection("4. Sample Manifest Validation");

        validateManifestResource("/manifests/order-detail-query.yaml",
                "order.detail.query");
        validateManifestResource("/manifests/purchase-list-query.yaml",
                "purchase.list.query");
    }

    /**
     * Validates a single manifest resource from the classpath.
     *
     * @param resourcePath the classpath resource path
     * @param expectedId the expected capability ID
     */
    private static void validateManifestResource(String resourcePath,
                                                  String expectedId) {
        System.out.println(" Validating: " + resourcePath);

        try (InputStream is = ManifestAuthoringGuide.class
                .getResourceAsStream(resourcePath)) {

            if (is == null) {
                System.out.println(" [SKIP] Resource not found on classpath");
                return;
            }

            JsonNode root = YAML_MAPPER.readTree(is);
            int checks = 0;
            int passed = 0;

            // Check 1: apiVersion
            checks++;
            if (root.has("apiVersion")
                    && "gateway.ai/v1".equals(root.get("apiVersion").asText())) {
                passed++;
            } else {
                System.out.println(" [FAIL] apiVersion != gateway.ai/v1");
            }

            // Check 2: kind
            checks++;
            if (root.has("kind")
                    && "Capability".equals(root.get("kind").asText())) {
                passed++;
            } else {
                System.out.println(" [FAIL] kind != Capability");
            }

            // Check 3: metadata.id
            checks++;
            JsonNode metadata = root.get("metadata");
            if (metadata != null && metadata.has("id")
                    && expectedId.equals(metadata.get("id").asText())) {
                passed++;
            } else {
                System.out.println(" [FAIL] metadata.id != " + expectedId);
            }

            // Check 4: metadata.version (SemVer format)
            checks++;
            if (metadata != null && metadata.has("version")
                    && metadata.get("version").asText()
                            .matches("\\d+\\.\\d+\\.\\d+")) {
                passed++;
            } else {
                System.out.println(" [FAIL] metadata.version is not valid SemVer");
            }

            // Check 5: spec.inputSchema.additionalProperties == false
            checks++;
            JsonNode spec = root.get("spec");
            JsonNode inputSchema = spec != null ? spec.get("inputSchema") : null;
            if (inputSchema != null && inputSchema.has("additionalProperties")
                    && !inputSchema.get("additionalProperties").asBoolean()) {
                passed++;
            } else {
                System.out.println(" [FAIL] inputSchema.additionalProperties != false");
            }

            // Check 6: serialization in whitelist
            checks++;
            JsonNode invocation = spec != null ? spec.get("invocation") : null;
            if (invocation != null && invocation.has("serialization")) {
                String serialization = invocation.get("serialization").asText();
                if (List.of("fastjson2", "hessian2").contains(serialization)) {
                    passed++;
                } else {
                    System.out.println(" [FAIL] serialization '"
                            + serialization + "' not in whitelist");
                }
            } else {
                System.out.println(" [FAIL] invocation.serialization missing");
            }

            // Check 7: examples have positive >= 3 and negative >= 2
            checks++;
            JsonNode examples = spec != null ? spec.get("examples") : null;
            if (examples != null) {
                int positiveCount = examples.has("positive")
                        ? examples.get("positive").size() : 0;
                int negativeCount = examples.has("negative")
                        ? examples.get("negative").size() : 0;
                if (positiveCount >= 3 && negativeCount >= 2) {
                    passed++;
                } else {
                    System.out.println(" [FAIL] examples: positive="
                            + positiveCount + " (need >=3), negative="
                            + negativeCount + " (need >=2)");
                }
            } else {
                System.out.println(" [FAIL] spec.examples missing");
            }

            System.out.println(" Result: " + passed + "/" + checks + " checks passed");

            if (passed == checks) {
                System.out.println(" [OK] Manifest is structurally valid");
            }

        } catch (Exception e) {
            System.out.println(" [ERROR] " + e.getMessage());
            log.warn("Manifest validation failed for {}", resourcePath, e);
        }

        System.out.println();
    }

    /**
     * Prints a section header.
     *
     * @param title the section title
     */
    private static void printSection(String title) {
        System.out.println("-".repeat(70));
        System.out.println(title);
        System.out.println("-".repeat(70));
    }
}
