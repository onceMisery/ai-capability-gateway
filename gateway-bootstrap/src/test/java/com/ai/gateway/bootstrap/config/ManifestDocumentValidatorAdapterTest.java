package com.ai.gateway.bootstrap.config;

import com.ai.gateway.domain.model.ValidationReport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ManifestDocumentValidatorAdapterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ManifestDocumentValidatorAdapter validator =
            new ManifestDocumentValidatorAdapter(objectMapper);

    @Test
    void shouldAcceptValidManifestDocument() throws Exception {
        ValidationReport report = validator.validate(validManifest());

        assertThat(report.valid()).isTrue();
        assertThat(report.errors()).isEmpty();
    }

    @Test
    void shouldRejectAdditionalRootProperty() throws Exception {
        ObjectNode document = validManifest();
        document.put("unexpected", true);

        ValidationReport report = validator.validate(document);

        assertThat(report.valid()).isFalse();
        assertThat(report.errors()).anyMatch(error -> error.contains("unexpected"));
    }

    @Test
    void shouldRejectMissingRequiredProperty() throws Exception {
        ObjectNode document = validManifest();
        document.remove("metadata");

        ValidationReport report = validator.validate(document);

        assertThat(report.valid()).isFalse();
        assertThat(report.errors()).anyMatch(error -> error.contains("metadata"));
    }

    private ObjectNode validManifest() throws Exception {
        return (ObjectNode) objectMapper.readTree("""
                {
                  "apiVersion": "gateway.ai/v1",
                  "kind": "Capability",
                  "metadata": {
                    "id": "order.detail.query",
                    "version": "1.0.0",
                    "owner": {"team": "order", "contact": "order@example.com"}
                  },
                  "spec": {
                    "displayName": "查询订单详情",
                    "description": "按订单号查询订单详情",
                    "examples": {
                      "positive": ["查询订单 A", "查看订单 B", "订单 C 的详情"],
                      "negative": ["创建订单", "取消订单"],
                      "synonyms": ["订单详情"]
                    },
                    "risk": "READ_ONLY",
                    "inputSchema": {"type": "object", "additionalProperties": false},
                    "invocation": {
                      "protocol": "DUBBO",
                      "registryRef": "test-registry",
                      "interfaceName": "com.example.OrderApi",
                      "version": "1.0.0",
                      "method": "query",
                      "parameterTypes": [],
                      "serialization": "hessian2",
                      "arguments": []
                    },
                    "output": {
                      "mode": "DIRECT",
                      "publicSchema": {"type": "object"},
                      "maxBytes": 4096
                    },
                    "resilience": {"timeoutMs": 1000, "retries": 0, "maxConcurrent": 10}
                  }
                }
                """);
    }
}
