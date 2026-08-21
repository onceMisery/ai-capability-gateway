package com.ai.gateway.bootstrap.config;

import com.ai.gateway.domain.model.ValidationReport;
import com.ai.gateway.domain.port.ManifestDocumentValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * 使用平台唯一 V1 Schema 校验原始 Manifest 文档。
 *
 * <p>Schema 在应用启动时加载并编译一次。资源缺失或 Schema 无法解析时直接阻止
 * 应用启动，避免导入接口在缺少契约的情况下继续运行。</p>
 * @author cmiracle@163.com
 */
@Component
public final class ManifestDocumentValidatorAdapter implements ManifestDocumentValidator {

    private static final String SCHEMA_RESOURCE =
            "/schema/capability-manifest-v1.schema.json";

    private final ObjectMapper objectMapper;
    private final JsonSchema manifestSchema;

    public ManifestDocumentValidatorAdapter(ObjectMapper objectMapper) {
        this.objectMapper = java.util.Objects.requireNonNull(
                objectMapper, "objectMapper must not be null");
        this.manifestSchema = loadSchema(objectMapper);
    }

    @Override
    public ValidationReport validate(Object document) {
        if (document == null) {
            return ValidationReport.failure(List.of("Manifest 文档不能为空"));
        }

        JsonNode documentNode = objectMapper.valueToTree(document);
        List<String> errors = manifestSchema.validate(documentNode).stream()
                .map(ValidationMessage::getMessage)
                .sorted()
                .toList();
        return errors.isEmpty()
                ? ValidationReport.success()
                : ValidationReport.failure(errors);
    }

    private static JsonSchema loadSchema(ObjectMapper objectMapper) {
        try (InputStream input = ManifestDocumentValidatorAdapter.class
                .getResourceAsStream(SCHEMA_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("未找到 Manifest Schema: " + SCHEMA_RESOURCE);
            }
            JsonNode schemaNode = objectMapper.readTree(input);
            SchemaValidatorsConfig config = SchemaValidatorsConfig.builder()
                    .formatAssertionsEnabled(true)
                    .build();
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                    .getSchema(schemaNode, config);
        } catch (IOException e) {
            throw new IllegalStateException("无法加载 Manifest Schema: " + SCHEMA_RESOURCE, e);
        }
    }
}
