package com.ai.gateway.adapter.web.manifest;

import com.ai.gateway.domain.model.ArgumentSource;
import com.ai.gateway.domain.model.CapabilityManifest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ManifestDocumentMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ManifestDocumentMapper mapper = new ManifestDocumentMapper(objectMapper);

    @Test
    void shouldMapExternalFieldsAndPreserveJsonScalarTypes() throws Exception {
        JsonNode document = objectMapper.readTree("""
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
                    "examples": {"positive": [], "negative": [], "synonyms": []},
                    "risk": "READ_ONLY",
                    "inputSchema": {"type": "object", "additionalProperties": false},
                    "authorization": {
                      "permissions": [],
                      "principalClaims": {},
                      "maxAuthAgeSeconds": 300,
                      "requiredAcr": "mfa",
                      "requiredAmr": ["otp"]
                    },
                    "invocation": {
                      "protocol": "DUBBO",
                      "registryRef": "test-registry",
                      "interfaceName": "com.example.OrderApi",
                      "version": "1.0.0",
                      "method": "query",
                      "parameterTypes": ["java.lang.Integer", "java.util.Map"],
                      "serialization": "hessian2",
                      "arguments": [
                        {
                          "position": 0,
                          "name": "limit",
                          "protocolType": "java.lang.Integer",
                          "source": "CONSTANT",
                          "value": 10
                        },
                        {
                          "position": 1,
                          "name": "request",
                          "protocolType": "java.util.Map",
                          "object": {
                            "/active": {"source": "CONSTANT", "value": true}
                          }
                        }
                      ],
                      "attachments": {
                        "locale": {"source": "CONSTANT", "value": "zh-CN"}
                      }
                    },
                    "output": {
                      "mode": "DIRECT",
                      "projection": [{"from": "/orderNo", "to": "/orderNo"}],
                      "publicSchema": {"type": "object"},
                      "redactions": [],
                      "maxBytes": 4096
                    },
                    "resilience": {"timeoutMs": 1000, "retries": 0, "maxConcurrent": 10}
                  }
                }
                """);

        CapabilityManifest manifest = mapper.toDomain(document);

        assertThat(manifest.spec().invocation().arguments().get(0).constantValue())
                .isEqualTo(10);
        assertThat(manifest.spec().invocation().arguments().get(1).objectBindings())
                .containsKey("/active");
        assertThat(manifest.spec().invocation().arguments().get(1)
                .objectBindings().get("/active").constantValue()).isEqualTo(true);
        assertThat(manifest.spec().output().projections()).hasSize(1);
        assertThat(manifest.spec().authorization().maxAuthAgeSeconds()).isEqualTo(300);
        assertThat(manifest.spec().authorization().requiredAmr()).containsExactly("otp");
        assertThat(manifest.spec().invocation().attachments().get("locale").source())
                .isEqualTo(ArgumentSource.CONSTANT);
        assertThat(manifest.spec().invocation().attachments().get("locale").constantValue())
                .isEqualTo("zh-CN");
    }
}
