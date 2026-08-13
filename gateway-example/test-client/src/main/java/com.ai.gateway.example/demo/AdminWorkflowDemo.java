package com.ai.gateway.example.demo;

import com.ai.gateway.example.client.GatewayApiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Demonstrates the control plane admin workflow (the design document, 15.3).
 *
 * <p>This example shows the complete capability lifecycle:
 * <ol>
 * <li>Import a Capability Manifest</li>
 * <li>Trigger validation (10-step pipeline)</li>
 * <li>Approve the validated capability</li>
 * <li>Publish to an environment (generates immutable snapshot)</li>
 * <li>Suspend a capability (emergency)</li>
 * <li>Rollback to a previous snapshot</li>
 * </ol>
 *
 * <p>The control plane lifecycle  ensures that no capability
 * reaches production without passing through the full governance pipeline:</p>
 * <pre>
 * DRAFT -> IMPORTED -> VALIDATED -> APPROVED -> PUBLISHED
 * |
 * SUSPENDED
 * </pre>
 *
 * <p>Key design constraints:</p>
 * <ul>
 * <li>: The 10-step validation pipeline runs at import time
 * and can be re-triggered via the validate endpoint.</li>
 * <li>: Approval requires an authorized approver identity.</li>
 * <li>: Publication generates an immutable, versioned snapshot.
 * The snapshot content cannot be modified after creation.</li>
 * <li>: Suspension is an emergency operation that immediately
 * removes the capability from the active snapshot.</li>
 * </ul>
 *
 * <p>Prerequisites:
 * <ul>
 * <li>Gateway running at http://localhost:8080</li>
 * <li>Admin JWT token with control-plane permissions</li>
 * </ul>
 *
 * <p>Run with:
 * <pre>{@code
 * java -cp gateway-example.jar com.ai.gateway.example.demo.AdminWorkflowDemo
 * }</pre>
 *
 * @since 0.1.0
 */
public class AdminWorkflowDemo {

    private static final Logger log = LoggerFactory.getLogger(AdminWorkflowDemo.class);

    private static final String DEFAULT_BASE_URL = "http://localhost:8080";
    private static final String DEFAULT_TOKEN = "admin-jwt-token";

    /** The capability ID used in this demo. */
    private static final String DEMO_CAPABILITY_ID = "order.detail.query";

    /** The capability version used in this demo. */
    private static final String DEMO_CAPABILITY_VERSION = "1.0.0";

    /**
     * Main entry point for the admin workflow demo.
     *
     * <p>Accepts optional command-line arguments:</p>
     * <ul>
     * <li>args[0] — gateway base URL (default: http://localhost:8080)</li>
     * <li>args[1] — admin JWT bearer token (default: admin-jwt-token)</li>
     * </ul>
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        String baseUrl = args.length > 0 ? args[0] : DEFAULT_BASE_URL;
        String token = args.length > 1 ? args[1] : DEFAULT_TOKEN;

        System.out.println("=".repeat(70));
        System.out.println("AI Capability Gateway — Admin Workflow Demo");
        System.out.println("the design document / ");
        System.out.println("=".repeat(70));
        System.out.println("Gateway URL: " + baseUrl);
        System.out.println();

        GatewayApiClient client = new GatewayApiClient(baseUrl, token);

        // Step 1: Import a Capability Manifest
        stepImportManifest(client);

        // Step 2: Trigger validation (10-step pipeline)
        stepValidateCapability(client);

        // Step 3: Approve the validated capability
        stepApproveCapability(client);

        // Step 4: Publish to an environment
        stepPublishRelease(client);

        // Step 5: Suspend a capability (emergency)
        stepSuspendCapability(client);

        // Step 6: Rollback to a previous snapshot
        stepRollback(client);

        System.out.println();
        System.out.println("=".repeat(70));
        System.out.println("Admin workflow demo complete.");
        System.out.println("=".repeat(70));
    }

    /**
     * Step 1: Import a Capability Manifest.
     *
     * <p>Loads the sample {@code order-detail-query.yaml} from the classpath
     * and submits it to the gateway's import endpoint.</p>
     *
     * <p>The import triggers the 10-step validation pipeline:</p>
     * <ol>
     * <li>JSON Schema validation against the versioned Manifest Schema</li>
     * <li>Capability ID format check (domain.resource.action)</li>
     * <li>Semantic version format check</li>
     * <li>Input Schema security constraints (additionalProperties: false)</li>
     * <li>Parameter binding consistency check</li>
     * <li>Serialization whitelist check</li>
     * <li>Output contract validation (envelope, projection, redaction)</li>
     * <li>Resilience policy bounds check</li>
     * <li>Semantic description completeness (positive/negative/synonyms)</li>
     * <li>Content digest computation (SHA-256)</li>
     * </ol>
     *
     * @param client the gateway API client
     */
    private static void stepImportManifest(GatewayApiClient client) {
        printStep(1, "Import Capability Manifest");

        String manifestYaml = loadClasspathResource("/manifests/order-detail-query.yaml");
        if (manifestYaml == null) {
            System.out.println(" [SKIP] Could not load manifest from classpath");
            return;
        }

        System.out.println(" Manifest: order-detail-query.yaml");
        System.out.println(" Capability ID: " + DEMO_CAPABILITY_ID);
        System.out.println(" Version: " + DEMO_CAPABILITY_VERSION);

        try {
            Map<String, Object> result = client.importManifest(manifestYaml);

            String status = (String) result.get("status");
            System.out.println(" Status: " + status);

            if ("IMPORTED".equals(status)) {
                System.out.println(" Manifest Digest: " + result.get("manifestDigest"));
                System.out.println(" Validation: " + result.get("validationReport"));
            } else if ("REJECTED".equals(status)) {
                System.out.println(" Error: " + result.get("error"));
                System.out.println(" Validation: " + result.get("validationReport"));
            } else {
                System.out.println(" Full response: " + result);
            }
        } catch (GatewayApiClient.GatewayApiException e) {
            System.out.println(" [ERROR] " + e.getMessage());
            log.warn("Manifest import failed", e);
        }

        System.out.println();
    }

    /**
     * Step 2: Trigger validation.
     *
     * <p>Re-validates an existing manifest version. This is useful when
     * the validation rules have been updated and existing manifests need
     * to be re-checked.</p>
     *
     * @param client the gateway API client
     */
    private static void stepValidateCapability(GatewayApiClient client) {
        printStep(2, "Validate Capability (10-step pipeline)");

        try {
            Map<String, Object> result = client.validateCapability(
                    DEMO_CAPABILITY_ID, DEMO_CAPABILITY_VERSION);

            String status = (String) result.get("status");
            System.out.println(" Status: " + status);
            System.out.println(" Validation Report: " + result.get("validationReport"));
        } catch (GatewayApiClient.GatewayApiException e) {
            System.out.println(" [ERROR] " + e.getMessage());
            log.warn("Validation failed", e);
        }

        System.out.println();
    }

    /**
     * Step 3: Approve the validated capability.
     *
     * <p>Approval transitions the manifest from VALIDATED to APPROVED state.
     * Only APPROVED capabilities are eligible for publication.</p>
     *
     * <p>The approval record includes the approver identity and is stored
     * in the audit trail .</p>
     *
     * @param client the gateway API client
     */
    private static void stepApproveCapability(GatewayApiClient client) {
        printStep(3, "Approve Capability");

        try {
            Map<String, Object> result = client.approveCapability(
                    DEMO_CAPABILITY_ID, DEMO_CAPABILITY_VERSION);

            String status = (String) result.get("status");
            System.out.println(" Status: " + status);

            if ("APPROVED".equals(status)) {
                System.out.println(" Capability ID: " + result.get("capabilityId"));
                System.out.println(" Version: " + result.get("capabilityVersion"));
                System.out.println(" Risk Level: " + result.get("riskLevel"));
            } else {
                System.out.println(" Message: " + result.get("message"));
            }
        } catch (GatewayApiClient.GatewayApiException e) {
            System.out.println(" [ERROR] " + e.getMessage());
            log.warn("Approval failed", e);
        }

        System.out.println();
    }

    /**
     * Step 4: Publish to an environment.
     *
     * <p>Publication generates an immutable catalog snapshot containing all
     * APPROVED capabilities. Key properties:</p>
     * <ul>
     * <li>The snapshot version is monotonically increasing.</li>
     * <li>The snapshot content cannot be modified after creation.</li>
     * <li>The snapshot includes a content digest for integrity verification.</li>
     * <li>The runtime data plane atomically switches to the new snapshot.</li>
     * </ul>
     *
     * @param client the gateway API client
     */
    private static void stepPublishRelease(GatewayApiClient client) {
        printStep(4, "Publish Release (immutable snapshot)");

        try {
            Map<String, Object> result = client.publishRelease("production");

            String status = (String) result.get("status");
            System.out.println(" Status: " + status);

            if ("PUBLISHED".equals(status)) {
                System.out.println(" Snapshot Version: " + result.get("snapshotVersion"));
                System.out.println(" (Snapshot is immutable and versioned)");
            } else {
                System.out.println(" Message: " + result.get("message"));
            }
        } catch (GatewayApiClient.GatewayApiException e) {
            System.out.println(" [ERROR] " + e.getMessage());
            log.warn("Publish failed", e);
        }

        System.out.println();
    }

    /**
     * Step 5: Suspend a capability.
     *
     * <p>Suspension is an emergency operation that immediately removes
     * the capability from the active catalog snapshot. A new snapshot
     * version is generated without the suspended capability.</p>
     *
     * <p>Use cases:</p>
     * <ul>
     * <li>Security vulnerability discovered in the downstream API</li>
     * <li>Provider is returning incorrect data</li>
     * <li>Compliance requirement to disable access immediately</li>
     * </ul>
     *
     * @param client the gateway API client
     */
    private static void stepSuspendCapability(GatewayApiClient client) {
        printStep(5, "Suspend Capability (emergency)");

        try {
            Map<String, Object> result = client.suspendCapability(
                    DEMO_CAPABILITY_ID,
                    "Emergency: Provider returning incorrect data");

            String status = (String) result.get("status");
            System.out.println(" Status: " + status);

            if ("SUSPENDED".equals(status)) {
                System.out.println(" Capability ID: " + result.get("capabilityId"));
                System.out.println(" New Snapshot Version: " + result.get("newSnapshotVersion"));
                System.out.println(" (Capability removed from active catalog)");
            } else {
                System.out.println(" Message: " + result.get("message"));
            }
        } catch (GatewayApiClient.GatewayApiException e) {
            System.out.println(" [ERROR] " + e.getMessage());
            log.warn("Suspension failed", e);
        }

        System.out.println();
    }

    /**
     * Step 6: Rollback to a previous snapshot.
     *
     * <p>Rollback creates a new snapshot version that restores the catalog
     * state to a historical snapshot. This is useful when a recent
     * publication introduced issues and needs to be reverted.</p>
     *
     * <p>Note: Rollback does not delete the problematic snapshot; it
     * creates a new snapshot with the historical content. The full
     * history is preserved for audit purposes .</p>
     *
     * @param client the gateway API client
     */
    private static void stepRollback(GatewayApiClient client) {
        printStep(6, "Rollback to Previous Snapshot");

        // In a real scenario, you would query the snapshot history first
        // to determine which version to roll back to. Here we use a
        // placeholder version.
        long targetVersion = 1L;

        System.out.println(" Target Snapshot Version: " + targetVersion);
        System.out.println(" (In production, query snapshot history first)");

        // Note: The GatewayApiClient does not have a rollback method
        // in the current implementation. This step demonstrates the
        // concept. In a real implementation, you would call:
        // POST /admin/v1/releases:rollback
        // { "targetSnapshotVersion": 1, "environment": "production" }
        System.out.println(" [INFO] Rollback endpoint: POST /admin/v1/releases:rollback");
        System.out.println(" [INFO] Request: {\"targetSnapshotVersion\": "
                + targetVersion + ", \"environment\": \"production\"}");

        System.out.println();
    }

    /**
     * Loads a classpath resource as a UTF-8 string.
     *
     * @param resourcePath the classpath resource path (e.g., "/manifests/order-detail-query.yaml")
     * @return the resource content, or null if not found
     */
    private static String loadClasspathResource(String resourcePath) {
        try (InputStream is = AdminWorkflowDemo.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                log.warn("Classpath resource not found: {}", resourcePath);
                return null;
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Failed to read classpath resource: {}", resourcePath, e);
            return null;
        }
    }

    /**
     * Prints a numbered step header for the demo output.
     *
     * @param stepNumber the step number
     * @param title the step title
     */
    private static void printStep(int stepNumber, String title) {
        System.out.println("-".repeat(70));
        System.out.println("Step " + stepNumber + ": " + title);
        System.out.println("-".repeat(70));
    }
}
