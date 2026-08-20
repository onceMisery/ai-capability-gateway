package com.ai.gateway.cli;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * Manifest 生成命令使用的强类型输入模型。
 */
final class GenerationModels {

    private GenerationModels() {
    }

    record DescriptorDocument(
            String descriptorVersion,
            String generatorVersion,
            List<CapabilityDescriptor> capabilities) {
    }

    record CapabilityDescriptor(
            String id,
            String version,
            String risk,
            String policyRef,
            String displayName,
            String description,
            String protocol,
            String interfaceName,
            String method,
            String inputSchemaResource,
            List<ArgumentDescriptor> arguments,
            OutputDescriptor output) {
    }

    record ArgumentDescriptor(
            int position,
            String name,
            String description,
            String protocolType,
            String jsonType,
            String source,
            String sourcePath,
            String converter,
            String constantValueJson,
            List<FieldDescriptor> object) {

        boolean composite() {
            return object != null && !object.isEmpty();
        }
    }

    record FieldDescriptor(
            String targetPath,
            String source,
            String sourcePath,
            String converter,
            String constantValueJson) {
    }

    record OutputDescriptor(
            String mode,
            String envelopeProfile,
            String schemaResource,
            int maxBytes,
            List<ProjectionDescriptor> projection,
            List<RedactionDescriptor> redactions) {
    }

    record ProjectionDescriptor(String from, String to) {
    }

    record RedactionDescriptor(String path, String method) {
    }

    record GovernanceConfig(Map<String, GovernancePolicy> policies) {
    }

    record GovernancePolicy(
            Owner owner,
            List<String> permissions,
            Map<String, PrincipalClaim> principalClaims,
            Examples examples,
            List<String> tags) {
    }

    record Owner(String team, String contact) {
    }

    record PrincipalClaim(String type, boolean required) {
    }

    record Examples(
            List<String> positive,
            List<String> negative,
            List<String> synonyms) {
    }

    record EnvironmentProfile(
            Map<String, ProviderProfile> providers,
            Map<String, EnvelopeProfile> envelopeProfiles) {
    }

    record ProviderProfile(
            String registryRef,
            String serviceVersion,
            String group,
            String serialization,
            Resilience resilience) {
    }

    record Resilience(long timeoutMs, int retries, int maxConcurrent) {
    }

    record EnvelopeProfile(
            String codePath,
            List<JsonNode> successValues,
            String dataPath,
            String messagePath) {
    }
}
