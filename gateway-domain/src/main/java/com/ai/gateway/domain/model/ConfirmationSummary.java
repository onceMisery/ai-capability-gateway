package com.ai.gateway.domain.model;

import java.util.List;

/**
 * The system-generated confirmation summary presented to the submitter
 * during the confirmation phase of the control plane.
 *
 * <p>Specifies that after a Manifest passes all 10 automatic
 * validation steps, the system generates a confirmation
 * summary containing at minimum:</p>
 *
 * <ul>
 * <li>Capability ID, version, and risk level.</li>
 * <li>Protocol binding summary: interface name, method, serialization.</li>
 * <li>Model-visible field list (MODEL fields in inputSchema).</li>
 * <li>Principal-injected field list (e.g., orgId).</li>
 * <li>Output projection fields and redaction rules.</li>
 * <li>Required permission strings.</li>
 * <li>Compatibility test result.</li>
 * <li>Manifest content SHA-256 digest.</li>
 * </ul>
 *
 * <p>The confirmation record must bind to the Manifest digest, confirmer,
 * time, environment, and opinion (confirm or reject). When Manifest content
 * changes, old confirmations are automatically invalidated.</p>
 *
 * <p>This simplified flow applies to READ_ONLY capabilities only. WRITE_LOW
 * and WRITE_HIGH require independent security review and dual approval
 *.</p>
 *
 * @param capabilityId the capability identifier
 * @param version the semantic version
 * @param risk the risk level
 * @param interfaceName the protocol interface name
 * @param method the protocol method name
 * @param serialization the serialization method
 * @param modelVisibleFields the list of MODEL-sourced field paths
 * @param principalInjectedFields the list of PRINCIPAL-sourced field paths
 * @param outputProjections the output projection mappings
 * @param redactions the output redaction rules
 * @param requiredPermissions the required permission strings
 * @param compatibilityTestResult the compatibility test outcome summary
 * @param manifestSha256 the SHA-256 digest of the Manifest content
 * @since 0.1.0
 */
public record ConfirmationSummary(
        String capabilityId,
        String version,
        RiskLevel risk,
        String interfaceName,
        String method,
        String serialization,
        List<String> modelVisibleFields,
        List<String> principalInjectedFields,
        List<ProjectionMapping> outputProjections,
        List<RedactionRule> redactions,
        List<String> requiredPermissions,
        String compatibilityTestResult,
        String manifestSha256
) {

    /**
     * Compact constructor performing defensive copying.
     *
     * @param capabilityId the capability ID
     * @param version the version
     * @param risk the risk level
     * @param interfaceName the interface name
     * @param method the method name
     * @param serialization the serialization
     * @param modelVisibleFields the model-visible fields
     * @param principalInjectedFields the principal-injected fields
     * @param outputProjections the output projections
     * @param redactions the redaction rules
     * @param requiredPermissions the required permissions
     * @param compatibilityTestResult the compatibility test result
     * @param manifestSha256 the manifest SHA-256 digest
     */
    public ConfirmationSummary {
        java.util.Objects.requireNonNull(capabilityId, "capabilityId must not be null");
        java.util.Objects.requireNonNull(version, "version must not be null");
        java.util.Objects.requireNonNull(risk, "risk must not be null");
        modelVisibleFields = List.copyOf(modelVisibleFields);
        principalInjectedFields = List.copyOf(principalInjectedFields);
        outputProjections = List.copyOf(outputProjections);
        redactions = List.copyOf(redactions);
        requiredPermissions = List.copyOf(requiredPermissions);
    }
}
