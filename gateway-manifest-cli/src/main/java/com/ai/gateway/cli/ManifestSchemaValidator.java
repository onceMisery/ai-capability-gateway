package com.ai.gateway.cli;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

/**
 * Capability Manifest 离线 Schema 校验器。
 *
 * <p>从 classpath 加载 JSON Schema 2020-12 版本的
 * {@code capability-manifest-v1.schema.json}，只校验机器可判定的文档结构；
 * 跨字段语义约束仍由领域契约校验器负责。</p>
 *
 * <p>实例不可变且可复用，Schema 只在构造时解析一次。</p>
 */
public final class ManifestSchemaValidator {

    /** Capability Manifest JSON Schema 的 classpath 路径。 */
    private static final String SCHEMA_CLASSPATH = "/schema/capability-manifest-v1.schema.json";

    private final ObjectMapper jsonMapper = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory())
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private final JsonSchema schema;

    public ManifestSchemaValidator() {
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        try (InputStream in = ManifestSchemaValidator.class.getResourceAsStream(SCHEMA_CLASSPATH)) {
            if (in == null) {
                throw new IllegalStateException(
                        "Capability Manifest schema not found on classpath: " + SCHEMA_CLASSPATH);
            }
            this.schema = factory.getSchema(in);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load Capability Manifest schema", e);
        }
    }

    /**
     * 校验 JSON 格式的 Manifest 文档。
     *
     * @param manifestJson JSON 格式的 Manifest 文档
     * @return 可读的校验错误列表；为空表示文档合法
     * @throws IOException 输入无法解析为 JSON 时抛出
     */
    public List<String> validate(String manifestJson) throws IOException {
        JsonNode node = jsonMapper.readTree(manifestJson);
        return validateNode(node);
    }

    /**
     * 将 YAML 格式的 Manifest 转换为 JSON 树后执行校验。
     *
     * @param manifestYaml YAML 格式的 Manifest 文档
     * @return 可读的校验错误列表；为空表示文档合法
     * @throws IOException 输入无法解析为 YAML 时抛出
     */
    public List<String> validateYaml(String manifestYaml) throws IOException {
        JsonNode node = yamlMapper.readTree(manifestYaml);
        return validateNode(node);
    }

    /**
     * 校验已经解析完成的 Manifest JSON 树。
     *
     * @param node Manifest JSON 树
     * @return 校验错误；为空表示通过
     */
    public List<String> validate(JsonNode node) {
        return validateNode(node);
    }

    private List<String> validateNode(JsonNode node) {
        Set<ValidationMessage> messages = schema.validate(node);
        List<String> errors = new ArrayList<>(messages.size());
        for (ValidationMessage message : messages) {
            errors.add(message.getMessage());
        }
        errors.sort(String::compareTo);
        return errors;
    }
}
