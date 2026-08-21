package com.ai.gateway.example.manifest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * 能力清单（Capability Manifest）编写指南。
 *
 * <p>本类作为可执行的文档，展示：</p>
 * <ul>
 * <li>清单结构与必填字段</li>
 * <li>能力 ID 与版本化规则</li>
 * <li>带安全约束的入参 Schema 编写</li>
 * <li>参数绑定：简单与复合</li>
 * <li>受控类型转换器</li>
 * <li>Dubbo 协议绑定</li>
 * <li>出参契约：Envelope、投影、脱敏</li>
 * <li>语义描述要求</li>
 * </ul>
 *
 * <p>运行本类可打印编写指南，并校验本模块随附的示例清单：</p>
 * <pre>{@code
 * java -cp gateway-example.jar com.ai.gateway.example.manifest.ManifestAuthoringGuide
 * }</pre>
 *
 * @author cmiracle@163.com
 * @since 0.1.0
 */
@Slf4j
public class ManifestAuthoringGuide {

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    /**
     * 入口方法。打印清单编写指南并校验示例清单。
     *
     * @param args 命令行参数（未使用）
     */
    public static void main(String[] args) {
        System.out.println("=".repeat(70));
        System.out.println("AI Capability Gateway — Manifest Authoring Guide");
        System.out.println("设计文档 / ");
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
     * 打印清单结构与必填字段。
     *
     * <p>能力清单是唯一的、机器可校验的契约，它将受治理的微服务 API 转化为
     * 可经自然语言发现的能力。其采用 YAML 或 JSON 格式，并由带版本的 JSON Schema 校验。</p>
     *
     * <p>顶层结构：</p>
     * <pre>
     * apiVersion: gateway.ai/v1 # 清单规范版本
     * kind: Capability # 固定为 "Capability"
     * metadata: # 身份与归属
     * id: domain.resource.action # 稳定能力标识
     * version: 1.0.0 # 语义化版本（SemVer）
     * owner: # 责任团队
     * team: team-name
     * contact: team@example.com
     * tags: [tag1, tag2] # 可选受控标签
     * spec: # 执行配置
     * displayName: ... # 面向用户的名称
     * description: ... # 单一业务动作描述
     * examples: ... # 正向、负向、同义词
     * risk: READ_ONLY # 风险等级
     * inputSchema: ... # 模型可见的 JSON Schema
     * invocation: ... # 协议绑定
     * output: ... # 出参契约
     * resilience: ... # 超时、重试、并发
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
     * 打印参数绑定规则。
     *
     * <p>网关强制区分模型可见与模型不可见参数。只有 MODEL 来源的参才出现在公开
     * 入参 Schema 中。</p>
     *
     * <h3>参数来源</h3>
     * <table border="1">
     * <tr><th>Source</th><th>含义</th><th>模型可见</th></tr>
     * <tr><td>MODEL</td><td>来自 LLM 结构化输出</td><td>是</td></tr>
     * <tr><td>PRINCIPAL</td><td>来自已鉴权的 Principal</td><td>否</td></tr>
     * <tr><td>CONSTANT</td><td>来自已确认的清单常量</td><td>否</td></tr>
     * <tr><td>SYSTEM</td><td>平台上下文（traceId 等）</td><td>否</td></tr>
     * </table>
     *
     * <h3>绑定模式</h3>
     * <ul>
     * <li><strong>简单绑定</strong>：source + sourcePath，用于单个值</li>
     * <li><strong>复合绑定</strong>：对象映射，用于混合来源 DTO</li>
     * </ul>
     *
     * <h3>受控类型转换器</h3>
     * <ul>
     * <li>ISO_DATE_TO_EPOCH_MILLIS — ISO-8601 字符串转 epoch 毫秒</li>
     * <li>ENUM_UPPERCASE — 将枚举字符串规范化到大写</li>
     * <li>STRING_TRIM — 去除空白</li>
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
     * 打印清单编写的安全约束。
     *
     * <h3>入参 Schema 安全</h3>
     * <ul>
     * <li>{@code additionalProperties: false} 为强制要求</li>
     * <li>PRINCIPAL 来源字段不得出现在 inputSchema 中</li>
     * <li>字符串字段应设 maxLength 与 pattern 约束</li>
     * <li>数值字段应设 minimum/maximum 边界</li>
     * </ul>
     *
     * <h3>出参契约安全</h3>
     * <ul>
     * <li>投影白名单：未映射字段不会离开网关</li>
     * <li>脱敏规则：PARTIAL_MASK、HASH、DELETE</li>
     * <li>publicSchema 校验最终输出</li>
     * <li>maxBytes 强制响应大小限制</li>
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
     * 校验本模块随附的示例清单。
     *
     * <p>从 classpath 加载 {@code order-detail-query.yaml} 与
     * {@code purchase-list-query.yaml}，并执行基础结构校验：</p>
     * <ul>
     * <li>YAML 语法合法</li>
     * <li>必填顶层字段齐全</li>
     * <li>inputSchema 含 additionalProperties: false</li>
     * <li>PRINCIPAL 字段不在 inputSchema 中</li>
     * <li>序列化方式在白名单中</li>
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
     * 校验 classpath 中的单个清单资源。
     *
     * @param resourcePath classpath 资源路径
     * @param expectedId 期望的能力 ID
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

            // 检查 1：apiVersion
            checks++;
            if (root.has("apiVersion")
                    && "gateway.ai/v1".equals(root.get("apiVersion").asText())) {
                passed++;
            } else {
                System.out.println(" [FAIL] apiVersion != gateway.ai/v1");
            }

            // 检查 2：kind
            checks++;
            if (root.has("kind")
                    && "Capability".equals(root.get("kind").asText())) {
                passed++;
            } else {
                System.out.println(" [FAIL] kind != Capability");
            }

            // 检查 3：metadata.id
            checks++;
            JsonNode metadata = root.get("metadata");
            if (metadata != null && metadata.has("id")
                    && expectedId.equals(metadata.get("id").asText())) {
                passed++;
            } else {
                System.out.println(" [FAIL] metadata.id != " + expectedId);
            }

            // 检查 4：metadata.version（SemVer 格式）
            checks++;
            if (metadata != null && metadata.has("version")
                    && metadata.get("version").asText()
                            .matches("\\d+\\.\\d+\\.\\d+")) {
                passed++;
            } else {
                System.out.println(" [FAIL] metadata.version is not valid SemVer");
            }

            // 检查 5：spec.inputSchema.additionalProperties == false
            checks++;
            JsonNode spec = root.get("spec");
            JsonNode inputSchema = spec != null ? spec.get("inputSchema") : null;
            if (inputSchema != null && inputSchema.has("additionalProperties")
                    && !inputSchema.get("additionalProperties").asBoolean()) {
                passed++;
            } else {
                System.out.println(" [FAIL] inputSchema.additionalProperties != false");
            }

            // 检查 6：serialization 在白名单中
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

            // 检查 7：examples 含 positive >= 3 与 negative >= 2
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
     * 打印分段标题。
     *
     * @param title 分段标题
     */
    private static void printSection(String title) {
        System.out.println("-".repeat(70));
        System.out.println(title);
        System.out.println("-".repeat(70));
    }
}
