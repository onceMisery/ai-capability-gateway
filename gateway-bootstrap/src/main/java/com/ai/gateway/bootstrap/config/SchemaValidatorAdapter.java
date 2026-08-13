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
 * Bootstrap adapter implementing {@link SchemaValidator} using the
 * <a href="https://github.com/networknt/json-schema-validator">networknt
 * JSON Schema Validator</a> with JSON Schema 2020-12 support.
 *
 * <p>This adapter validates JSON-compatible data trees against JSON Schema
 * 2020-12 documents. It is used for:</p>
 * <ul>
 * <li>Input Schema validation — {@code spec.inputSchema}.</li>
 * <li>LLM output validation.</li>
 * <li>Public output Schema validation.</li>
 * <li>Manifest structural validation.</li>
 * </ul>
 *
 * <p>A report is considered valid only if {@code errors} is empty.
 * Warnings are informational and do not block processing.</p>
 *
 * @since 0.1.0
 */
@Component
public class SchemaValidatorAdapter implements SchemaValidator {

    private static final Logger log = LoggerFactory.getLogger(SchemaValidatorAdapter.class);

    private final JsonSchemaFactory schemaFactory;
    private final ObjectMapper objectMapper;
    private final SchemaValidatorsConfig config;

    /**
     * Constructs a new SchemaValidatorAdapter configured for JSON Schema
     * 2020-12 (Draft 2020-12).
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
