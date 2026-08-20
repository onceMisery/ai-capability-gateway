package com.ai.gateway.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManifestDraftGeneratorTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldGenerateSchemaValidManifestDraft() throws Exception {
        Fixture fixture = fixture(validGovernance(), validProfile(), "schemas/output.json");

        ManifestDraftGenerator.GenerationResult result =
                new ManifestDraftGenerator().generate(fixture.request());

        assertThat(result.successful()).isTrue();
        assertThat(result.generated()).containsExactly("order.query");
        JsonNode manifest = OBJECT_MAPPER.readTree(fixture.output().toFile());
        assertThat(manifest.at("/spec/invocation/arguments/0/value").booleanValue()).isTrue();
        assertThat(manifest.at("/metadata/version").asText()).isEqualTo("1.0.0");
        assertThat(manifest.at("/spec/invocation/version").asText()).isEqualTo("2.1.0");
        assertThat(manifest.path("spec").path("output").has("projection")).isTrue();
        assertThat(OBJECT_MAPPER.readTree(fixture.report().toFile())
                .path("success").booleanValue()).isTrue();
    }

    @Test
    void shouldReportMissingGovernancePolicyAndRemoveStaleDraft() throws Exception {
        Fixture fixture = fixture("policies: {}\n", validProfile(), "schemas/output.json");
        Files.createDirectories(fixture.output().getParent());
        Files.writeString(fixture.output(), "stale", StandardCharsets.UTF_8);

        ManifestDraftGenerator.GenerationResult result =
                new ManifestDraftGenerator().generate(fixture.request());

        assertThat(result.successful()).isFalse();
        assertThat(result.failures().get("order.query"))
                .anyMatch(error -> error.contains("governance policy not found"));
        assertThat(fixture.output()).doesNotExist();
    }

    @Test
    void shouldReportMissingProviderProfile() throws Exception {
        Fixture fixture = fixture(
                validGovernance(),
                "providers: {}\nenvelopeProfiles: {}\n",
                "schemas/output.json");

        ManifestDraftGenerator.GenerationResult result =
                new ManifestDraftGenerator().generate(fixture.request());

        assertThat(result.successful()).isFalse();
        assertThat(result.failures().get("order.query"))
                .anyMatch(error -> error.contains("provider profile not found"));
    }

    @Test
    void shouldRejectSchemaPathTraversal() throws Exception {
        Path outsideSchema = temporaryDirectory.resolve("outside.json");
        Files.writeString(outsideSchema, outputSchema(), StandardCharsets.UTF_8);
        Fixture fixture = fixture(validGovernance(), validProfile(), "../outside.json");

        ManifestDraftGenerator.GenerationResult result =
                new ManifestDraftGenerator().generate(fixture.request());

        assertThat(result.successful()).isFalse();
        assertThat(result.failures().get("order.query"))
                .anyMatch(error -> error.contains("escapes schemas root"));
    }

    @Test
    void shouldRejectDuplicateJsonKeys() {
        String duplicateManifest = """
                {"apiVersion":"gateway.ai/v1","apiVersion":"gateway.ai/v2"}
                """;

        assertThatThrownBy(() -> new ManifestSchemaValidator().validate(duplicateManifest))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("Duplicate field");
    }

    @Test
    void shouldGenerateMultipleDraftsAndReportFieldSources() throws Exception {
        Fixture fixture = fixtureWithDescriptor(
                batchDescriptor(), validBatchGovernance(), validProfile());

        ManifestDraftGenerator.GenerationResult result =
                new ManifestDraftGenerator().generate(fixture.request());

        assertThat(result.successful()).isTrue();
        assertThat(result.generated()).containsExactly("order.query", "order.search");
        assertThat(fixture.request().outputDirectory().resolve("order.query.json"))
                .isRegularFile();
        assertThat(fixture.request().outputDirectory().resolve("order.search.json"))
                .isRegularFile();
        JsonNode report = OBJECT_MAPPER.readTree(fixture.report().toFile());
        assertThat(report.path("generated")).hasSize(2);
        assertThat(report.at("/fieldSources/order.query/metadata.version").asText())
                .isEqualTo("descriptor:capabilities[order.query].version");
        assertThat(report.at("/fieldSources/order.search/spec.invocation").asText())
                .isEqualTo("profile:providers[com.example.OrderApi]");
    }

    @Test
    void shouldRejectDuplicateYamlKeys() throws Exception {
        String duplicateGovernance = validGovernance() + """
                policies:
                  duplicate: {}
                """;
        Fixture fixture = fixture(
                duplicateGovernance, validProfile(), "schemas/output.json");

        assertThatThrownBy(() -> new ManifestDraftGenerator().generate(fixture.request()))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("Duplicate field");
    }

    @Test
    void shouldRejectNonScalarConstantFromDescriptor() throws Exception {
        ObjectNode descriptor = (ObjectNode) OBJECT_MAPPER.readTree(
                descriptor("schemas/output.json"));
        ((ObjectNode) descriptor.at("/capabilities/0/arguments/0"))
                .put("constantValueJson", "{}");
        Fixture fixture = fixtureWithDescriptor(
                OBJECT_MAPPER.writeValueAsString(descriptor),
                validGovernance(),
                validProfile());

        ManifestDraftGenerator.GenerationResult result =
                new ManifestDraftGenerator().generate(fixture.request());

        assertThat(result.successful()).isFalse();
        assertThat(result.failures().get("order.query"))
                .anyMatch(error -> error.contains("string, number, or boolean scalar"));
    }

    private Fixture fixture(String governance, String profile, String outputSchemaResource)
            throws Exception {
        return fixtureWithDescriptor(
                descriptor(outputSchemaResource), governance, profile);
    }

    private Fixture fixtureWithDescriptor(
            String descriptorContent, String governance, String profile) throws Exception {
        Path root = Files.createTempDirectory(temporaryDirectory, "generation-");
        Path schemas = Files.createDirectories(root.resolve("schemas"));
        Files.writeString(schemas.resolve("output.json"), outputSchema(), StandardCharsets.UTF_8);

        Path descriptor = root.resolve("capabilities.json");
        Path governanceFile = root.resolve("governance.yml");
        Path profileFile = root.resolve("profile.yml");
        Path outputDirectory = root.resolve("drafts");
        Path report = root.resolve("report.json");
        Files.writeString(
                descriptor,
                descriptorContent,
                StandardCharsets.UTF_8);
        Files.writeString(governanceFile, governance, StandardCharsets.UTF_8);
        Files.writeString(profileFile, profile, StandardCharsets.UTF_8);

        ManifestDraftGenerator.GenerationRequest request =
                new ManifestDraftGenerator.GenerationRequest(
                        descriptor,
                        root,
                        governanceFile,
                        profileFile,
                        outputDirectory,
                        report);
        return new Fixture(
                request,
                outputDirectory.resolve("order.query.json"),
                report);
    }

    private static String descriptor(String outputSchemaResource) {
        return descriptorDocument(capabilityDescriptor(
                "order.query", "order.read", "query", outputSchemaResource));
    }

    private static String batchDescriptor() {
        return descriptorDocument(
                capabilityDescriptor(
                        "order.search", "order.search", "search", "schemas/output.json")
                        + ",\n"
                        + capabilityDescriptor(
                        "order.query", "order.read", "query", "schemas/output.json"));
    }

    private static String descriptorDocument(String capabilities) {
        return """
                {
                  "descriptorVersion": "1.0",
                  "generatorVersion": "0.1.0",
                  "capabilities": [%s]
                }
                """.formatted(capabilities);
    }

    private static String capabilityDescriptor(
            String id, String policyRef, String method, String outputSchemaResource) {
        return """
                  {
                    "id": "%s",
                    "version": "1.0.0",
                    "risk": "READ_ONLY",
                    "policyRef": "%s",
                    "displayName": "查询订单",
                    "description": "按固定条件查询订单",
                    "protocol": "DUBBO",
                    "interfaceName": "com.example.OrderApi",
                    "method": "%s",
                    "inputSchemaResource": "",
                    "arguments": [{
                      "position": 0,
                      "name": "active",
                      "description": "是否启用",
                      "protocolType": "java.lang.Boolean",
                      "jsonType": "boolean",
                      "source": "CONSTANT",
                      "sourcePath": "",
                      "converter": "",
                      "constantValueJson": "true",
                      "object": null
                    }],
                    "output": {
                      "mode": "DIRECT",
                      "envelopeProfile": "",
                      "schemaResource": "%s",
                      "maxBytes": 4096,
                      "projection": [],
                      "redactions": []
                    }
                  }
                """.formatted(id, policyRef, method, outputSchemaResource);
    }

    private static String validGovernance() {
        return """
                policies:
                  order.read:
                    owner:
                      team: order
                      contact: order@example.com
                    permissions: [order:detail:read]
                    principalClaims: {}
                    examples:
                      positive: [查询订单A, 查询订单B, 查询订单C]
                      negative: [创建订单, 取消订单]
                      synonyms: [订单]
                    tags: [order]
                """;
    }

    private static String validProfile() {
        return """
                providers:
                  com.example.OrderApi:
                    registryRef: test-registry
                    serviceVersion: 2.1.0
                    group: ""
                    serialization: hessian2
                    resilience:
                      timeoutMs: 1000
                      retries: 1
                      maxConcurrent: 10
                envelopeProfiles: {}
                """;
    }

    private static String validBatchGovernance() {
        return """
                policies:
                  order.read:
                    owner:
                      team: order
                      contact: order@example.com
                    permissions: [order:detail:read]
                    principalClaims: {}
                    examples:
                      positive: [查询订单A, 查询订单B, 查询订单C]
                      negative: [创建订单, 取消订单]
                      synonyms: [订单]
                    tags: [order]
                  order.search:
                    owner:
                      team: order
                      contact: order@example.com
                    permissions: [order:detail:read]
                    principalClaims: {}
                    examples:
                      positive: [搜索订单A, 搜索订单B, 搜索订单C]
                      negative: [创建订单, 取消订单]
                      synonyms: [订单搜索]
                    tags: [order]
                """;
    }

    private static String outputSchema() {
        return """
                {
                  "$schema": "https://json-schema.org/draft/2020-12/schema",
                  "type": "object",
                  "additionalProperties": false,
                  "properties": {}
                }
                """;
    }

    private record Fixture(
            ManifestDraftGenerator.GenerationRequest request,
            Path output,
            Path report) {
    }
}
