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
 * Offline schema validation utility for Capability Manifests (design document ).
 *
 * <p>Loads {@code capability-manifest-v1.schema.json} (JSON Schema 2020-12) from the
 * classpath and validates Manifest documents against it. This performs only the
 * machine-checkable structural validation; cross-field semantic
 * constraints are enforced separately by the Contract Validator.</p>
 *
 * <p>Instances are immutable and safe to reuse; the schema is parsed once at
 * construction time.</p>
 */
public final class ManifestSchemaValidator {

    /** Classpath location of the Capability Manifest JSON Schema. */
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
     * Validates a Manifest supplied as JSON.
     *
     * @param manifestJson the Manifest document serialized as JSON
     * @return a list of human-readable validation errors; empty when the document is valid
     * @throws IOException if the input cannot be parsed as JSON
     */
    public List<String> validate(String manifestJson) throws IOException {
        JsonNode node = jsonMapper.readTree(manifestJson);
        return validateNode(node);
    }

    /**
     * Validates a Manifest supplied as YAML by first converting it to a JSON tree.
     *
     * @param manifestYaml the Manifest document serialized as YAML
     * @return a list of human-readable validation errors; empty when the document is valid
     * @throws IOException if the input cannot be parsed as YAML
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
