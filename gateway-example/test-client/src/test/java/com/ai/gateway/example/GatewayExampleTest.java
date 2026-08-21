package com.ai.gateway.example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 网关示例模块的测试。校验随附的示例清单并演示测试模式。
 *
 * <p>这些测试验证 gateway-example 模块随附的示例能力清单是否符合设计文档中定义的
 * 结构与安全要求。</p>
 *
 * <p>演示的测试模式：</p>
 * <ul>
 * <li>YAML 解析与结构校验</li>
 * <li>必填字段存在性检查</li>
 * <li>安全约束校验（additionalProperties、PRINCIPAL 隔离）</li>
 * <li>序列化白名单强制</li>
 * <li>语义描述完整性</li>
 * </ul>
 *
 * @author cmiracle@163.com
 * @since 0.1.0
 */
class GatewayExampleTest {

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    /** 平台序列化白名单。 */
    private static final Set<String> SERIALIZATION_WHITELIST =
            Set.of("fastjson2", "hessian2");

    private static JsonNode orderDetailManifest;
    private static JsonNode purchaseListManifest;

    /**
     * 在所有测试执行前从 classpath 加载两份示例清单。
     *
     * @throws IOException 当清单无法读取时
     */
    @BeforeAll
    static void loadManifests() throws IOException {
        orderDetailManifest = loadYaml("/manifests/order-detail-query.yaml");
        purchaseListManifest = loadYaml("/manifests/purchase-list-query.yaml");
    }

    // ========================================================================
    // YAML Validity Tests
    // ========================================================================

    @Nested
    @DisplayName("YAML Validity")
    class YamlValidityTests {

        @Test
        @DisplayName("order-detail-query.yaml should be valid YAML")
        void orderDetailManifestShouldBeValidYaml() {
            assertThat(orderDetailManifest)
                    .as("order-detail-query.yaml should parse as valid YAML")
                    .isNotNull();
            assertThat(orderDetailManifest.isObject())
                    .as("Root node should be a YAML mapping")
                    .isTrue();
        }

        @Test
        @DisplayName("purchase-list-query.yaml should be valid YAML")
        void purchaseListManifestShouldBeValidYaml() {
            assertThat(purchaseListManifest)
                    .as("purchase-list-query.yaml should parse as valid YAML")
                    .isNotNull();
            assertThat(purchaseListManifest.isObject())
                    .as("Root node should be a YAML mapping")
                    .isTrue();
        }
    }

    // ========================================================================
    // Required Fields Tests
    // ========================================================================

    @Nested
    @DisplayName("Required Fields")
    class RequiredFieldsTests {

        @Test
        @DisplayName("Manifest should have all required top-level fields")
        void manifestShouldHaveRequiredFields() {
            // apiVersion、kind、metadata、spec 为必填项
            assertThat(orderDetailManifest.has("apiVersion"))
                    .as("apiVersion is required")
                    .isTrue();
            assertThat(orderDetailManifest.get("apiVersion").asText())
                    .isEqualTo("gateway.ai/v1");

            assertThat(orderDetailManifest.has("kind"))
                    .as("kind is required")
                    .isTrue();
            assertThat(orderDetailManifest.get("kind").asText())
                    .isEqualTo("Capability");

            assertThat(orderDetailManifest.has("metadata"))
                    .as("metadata is required")
                    .isTrue();

            assertThat(orderDetailManifest.has("spec"))
                    .as("spec is required")
                    .isTrue();
        }

        @Test
        @DisplayName("Metadata should have id, version, and owner")
        void metadataShouldHaveRequiredFields() {
            JsonNode metadata = orderDetailManifest.get("metadata");

            assertThat(metadata.has("id"))
                    .as("metadata.id is required")
                    .isTrue();
            assertThat(metadata.get("id").asText())
                    .as("Capability ID should follow domain.resource.action convention")
                    .matches("[a-z][a-z0-9]*\\.[a-z][a-z0-9]*\\.[a-z][a-z0-9]*");

            assertThat(metadata.has("version"))
                    .as("metadata.version is required")
                    .isTrue();
            assertThat(metadata.get("version").asText())
                    .as("Version should be valid SemVer")
                    .matches("\\d+\\.\\d+\\.\\d+");

            assertThat(metadata.has("owner"))
                    .as("metadata.owner is required")
                    .isTrue();
            assertThat(metadata.get("owner").has("team"))
                    .as("owner.team is required")
                    .isTrue();
            assertThat(metadata.get("owner").has("contact"))
                    .as("owner.contact is required")
                    .isTrue();
        }

        @Test
        @DisplayName("Spec should have all required fields")
        void specShouldHaveRequiredFields() {
            JsonNode spec = orderDetailManifest.get("spec");

            assertThat(spec.has("displayName"))
                    .as("spec.displayName is required")
                    .isTrue();
            assertThat(spec.has("description"))
                    .as("spec.description is required")
                    .isTrue();
            assertThat(spec.has("examples"))
                    .as("spec.examples is required")
                    .isTrue();
            assertThat(spec.has("risk"))
                    .as("spec.risk is required")
                    .isTrue();
            assertThat(spec.has("inputSchema"))
                    .as("spec.inputSchema is required")
                    .isTrue();
            assertThat(spec.has("invocation"))
                    .as("spec.invocation is required")
                    .isTrue();
            assertThat(spec.has("output"))
                    .as("spec.output is required")
                    .isTrue();
            assertThat(spec.has("resilience"))
                    .as("spec.resilience is required")
                    .isTrue();
        }
    }

    // ========================================================================
    // Input Schema Security Tests
    // ========================================================================

    @Nested
    @DisplayName("Input Schema Security")
    class InputSchemaSecurityTests {

        @Test
        @DisplayName("inputSchema should have additionalProperties: false")
        void inputSchemaShouldHaveAdditionalPropertiesFalse() {
            // additionalProperties: false 为强制要求
            JsonNode inputSchema = orderDetailManifest.get("spec").get("inputSchema");

            assertThat(inputSchema.has("additionalProperties"))
                    .as("inputSchema must declare additionalProperties")
                    .isTrue();
            assertThat(inputSchema.get("additionalProperties").asBoolean())
                    .as("additionalProperties must be false")
                    .isFalse();

            // Also check purchase list manifest
            JsonNode purchaseSchema = purchaseListManifest.get("spec").get("inputSchema");
            assertThat(purchaseSchema.get("additionalProperties").asBoolean())
                    .as("purchase-list inputSchema additionalProperties must be false")
                    .isFalse();
        }

        @Test
        @DisplayName("PRINCIPAL-sourced fields should not be in inputSchema")
        void principalFieldsShouldNotBeInInputSchema() {
            // 6.5：PRINCIPAL 来源字段（如 orgId）不得出现在模型可见的 inputSchema 中
            JsonNode spec = orderDetailManifest.get("spec");
            JsonNode inputSchema = spec.get("inputSchema");
            JsonNode properties = inputSchema.get("properties");

            // 从 arguments 中收集所有 PRINCIPAL 来源字段名
            JsonNode arguments = spec.get("invocation").get("arguments");
            for (JsonNode arg : arguments) {
                if (arg.has("source")
                        && "PRINCIPAL".equals(arg.get("source").asText())) {
                    String fieldName = arg.get("name").asText();
                    assertThat(properties.has(fieldName))
                            .as("PRINCIPAL field '" + fieldName
                                    + "' must not be in inputSchema")
                            .isFalse();
                }

                // 同时检查复合绑定中的 PRINCIPAL 字段
                if (arg.has("object")) {
                    JsonNode objectBindings = arg.get("object");
                    objectBindings.fields().forEachRemaining(entry -> {
                        JsonNode binding = entry.getValue();
                        if (binding.has("source")
                                && "PRINCIPAL".equals(
                                        binding.get("source").asText())) {
                            String fieldPath = entry.getKey();
                            String fieldName = fieldPath.startsWith("/")
                                    ? fieldPath.substring(1) : fieldPath;
                            assertThat(properties.has(fieldName))
                                    .as("PRINCIPAL composite field '" + fieldName
                                            + "' must not be in inputSchema")
                                    .isFalse();
                        }
                    });
                }
            }
        }
    }

    // ========================================================================
    // Serialization Whitelist Tests
    // ========================================================================

    @Nested
    @DisplayName("Serialization Whitelist")
    class SerializationWhitelistTests {

        @Test
        @DisplayName("Serialization should be in the platform whitelist")
        void serializationShouldBeInWhitelist() {
            // 序列化方式必须属于平台白名单
            JsonNode invocation = orderDetailManifest.get("spec").get("invocation");

            assertThat(invocation.has("serialization"))
                    .as("invocation.serialization is required")
                    .isTrue();

            String serialization = invocation.get("serialization").asText();
            assertThat(SERIALIZATION_WHITELIST)
                    .as("Serialization '" + serialization
                            + "' must be in the platform whitelist")
                    .contains(serialization);

            // Also check purchase list manifest
            JsonNode purchaseInvocation =
                    purchaseListManifest.get("spec").get("invocation");
            String purchaseSerialization =
                    purchaseInvocation.get("serialization").asText();
            assertThat(SERIALIZATION_WHITELIST)
                    .as("Purchase serialization must be in whitelist")
                    .contains(purchaseSerialization);
        }
    }

    // ========================================================================
    // Semantic Description Tests
    // ========================================================================

    @Nested
    @DisplayName("Semantic Descriptions")
    class SemanticDescriptionTests {

        @Test
        @DisplayName("Examples should have at least 3 positive and 2 negative")
        void examplesShouldMeetMinimumCounts() {
            // 至少 3 条正向、2 条负向，以及同义词
            JsonNode examples = orderDetailManifest.get("spec").get("examples");

            assertThat(examples.has("positive"))
                    .as("examples.positive is required")
                    .isTrue();
            assertThat(examples.get("positive").size())
                    .as("At least 3 positive examples required")
                    .isGreaterThanOrEqualTo(3);

            assertThat(examples.has("negative"))
                    .as("examples.negative is required")
                    .isTrue();
            assertThat(examples.get("negative").size())
                    .as("At least 2 negative examples required")
                    .isGreaterThanOrEqualTo(2);

            assertThat(examples.has("synonyms"))
                    .as("examples.synonyms is required")
                    .isTrue();
            assertThat(examples.get("synonyms").size())
                    .as("At least 1 synonym required")
                    .isGreaterThanOrEqualTo(1);
        }

        @Test
        @DisplayName("Purchase list examples should meet minimum counts")
        void purchaseListExamplesShouldMeetMinimumCounts() {
            JsonNode examples = purchaseListManifest.get("spec").get("examples");

            assertThat(examples.get("positive").size())
                    .as("At least 3 positive examples required")
                    .isGreaterThanOrEqualTo(3);
            assertThat(examples.get("negative").size())
                    .as("At least 2 negative examples required")
                    .isGreaterThanOrEqualTo(2);
        }
    }

    // ========================================================================
    // Output Contract Tests
    // ========================================================================

    @Nested
    @DisplayName("Output Contract")
    class OutputContractTests {

        @Test
        @DisplayName("Output should have mode, publicSchema, and maxBytes")
        void outputShouldHaveRequiredFields() {
            JsonNode output = orderDetailManifest.get("spec").get("output");

            assertThat(output.has("mode"))
                    .as("output.mode is required")
                    .isTrue();
            assertThat(output.get("mode").asText())
                    .isIn("ENVELOPE", "DIRECT");

            assertThat(output.has("publicSchema"))
                    .as("output.publicSchema is required")
                    .isTrue();

            assertThat(output.has("maxBytes"))
                    .as("output.maxBytes is required")
                    .isTrue();
            assertThat(output.get("maxBytes").asInt())
                    .as("maxBytes must be positive")
                    .isPositive();
        }

        @Test
        @DisplayName("ENVELOPE mode should have envelope config")
        void envelopeModeShouldHaveConfig() {
            JsonNode output = orderDetailManifest.get("spec").get("output");

            if ("ENVELOPE".equals(output.get("mode").asText())) {
                assertThat(output.has("envelope"))
                        .as("ENVELOPE mode requires envelope config")
                        .isTrue();

                JsonNode envelope = output.get("envelope");
                assertThat(envelope.has("codePath"))
                        .as("envelope.codePath is required")
                        .isTrue();
                assertThat(envelope.has("successValues"))
                        .as("envelope.successValues is required")
                        .isTrue();
                assertThat(envelope.has("dataPath"))
                        .as("envelope.dataPath is required")
                        .isTrue();
            }
        }
    }

    // ========================================================================
    // Resilience Policy Tests
    // ========================================================================

    @Nested
    @DisplayName("Resilience Policy")
    class ResiliencePolicyTests {

        @Test
        @DisplayName("Resilience should have timeoutMs, retries, and maxConcurrent")
        void resilienceShouldHaveRequiredFields() {
            JsonNode resilience = orderDetailManifest.get("spec").get("resilience");

            assertThat(resilience.has("timeoutMs"))
                    .as("resilience.timeoutMs is required")
                    .isTrue();
            assertThat(resilience.get("timeoutMs").asLong())
                    .as("timeoutMs must be positive")
                    .isPositive();

            assertThat(resilience.has("retries"))
                    .as("resilience.retries is required")
                    .isTrue();
            assertThat(resilience.get("retries").asInt())
                    .as("retries must be non-negative")
                    .isGreaterThanOrEqualTo(0);

            assertThat(resilience.has("maxConcurrent"))
                    .as("resilience.maxConcurrent is required")
                    .isTrue();
            assertThat(resilience.get("maxConcurrent").asInt())
                    .as("maxConcurrent must be positive")
                    .isPositive();
        }
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    /**
     * 从 classpath 加载 YAML 资源并解析为 JsonNode。
     *
     * @param resourcePath classpath 资源路径
     * @return 解析后的 YAML（JsonNode 形式）
     * @throws IOException 当资源无法读取或解析时
     */
    private static JsonNode loadYaml(String resourcePath) throws IOException {
        try (InputStream is = GatewayExampleTest.class
                .getResourceAsStream(resourcePath)) {
            assertThat(is)
                    .as("Classpath resource should exist: " + resourcePath)
                    .isNotNull();
            return YAML_MAPPER.readTree(is);
        }
    }
}
