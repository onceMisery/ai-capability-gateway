package com.ai.gateway.bootstrap.config;

import com.ai.gateway.domain.model.ValidationReport;
import com.ai.gateway.domain.port.SchemaValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 使用 <a href="https://github.com/networknt/json-schema-validator">networknt
 * JSON Schema Validator</a>（支持 JSON Schema 2020-12）实现 {@link SchemaValidator}
 * 的引导适配器。
 *
 * <p>该适配器针对 JSON Schema 2020-12 文档校验 JSON 兼容的数据树，用于：</p>
 * <ul>
 * <li>输入 Schema 校验 — {@code spec.inputSchema}。</li>
 * <li>LLM 输出校验。</li>
 * <li>公开输出 Schema 校验。</li>
 * <li>清单结构校验。</li>
 * </ul>
 *
 * <p>仅当 {@code errors} 为空时，报告才视为有效。警告仅作提示，不会阻断处理。</p>
 *
 * @since 0.1.0
 * @author cmiracle@163.com
 */
@Component
public class SchemaValidatorAdapter implements SchemaValidator {

    private static final Logger log = LoggerFactory.getLogger(SchemaValidatorAdapter.class);

    private final JsonSchemaFactory schemaFactory;
    private final ObjectMapper objectMapper;
    private final SchemaValidatorsConfig config;

    /**
     * 构造一个针对 JSON Schema 2020-12（Draft 2020-12）配置的
     * SchemaValidatorAdapter。
     */
    public SchemaValidatorAdapter() {
        this.schemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        this.objectMapper = new ObjectMapper();
        this.config = SchemaValidatorsConfig.builder()
                .formatAssertionsEnabled(true)
                .build();
        log.info("SchemaValidatorAdapter initialized: JSON Schema 2020-12 (networknt)");
    }

    @Override
    public ValidationReport validate(Map<String, Object> data, Map<String, Object> schema) {
        if (data == null) {
            return ValidationReport.failure(List.of("data must not be null"));
        }
        if (schema == null) {
            return ValidationReport.failure(List.of("schema must not be null"));
        }

        try {
            JsonNode schemaNode = objectMapper.valueToTree(schema);
            JsonSchema jsonSchema = schemaFactory.getSchema(schemaNode, config);

            JsonNode dataNode = objectMapper.valueToTree(data);
            Set<ValidationMessage> messages = jsonSchema.validate(dataNode);

            if (messages.isEmpty()) {
                return ValidationReport.success();
            }

            List<String> errors = new ArrayList<>(messages.size());
            for (ValidationMessage message : messages) {
                errors.add(message.getMessage());
            }

            return new ValidationReport(false, List.copyOf(errors), List.of());

        } catch (Exception e) {
            log.error("Schema validation failed with unexpected error: {}", e.getMessage(), e);
            return ValidationReport.failure(List.of(
                    "Schema validation error: " + e.getMessage()));
        }
    }
}
