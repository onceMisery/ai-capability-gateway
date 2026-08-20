package com.ai.gateway.adapter.web.manifest;

import com.ai.gateway.domain.model.CapabilityManifest;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Manifest 外部 JSON 契约到领域模型的唯一映射器。
 *
 * <p>外部 Schema 使用面向使用者的字段名，领域模型使用表达内部含义的字段名。
 * 映射集中在 Web 适配层，避免领域层依赖 Jackson，也避免不同入口各自维护一套
 * 重命名规则。</p>
 *
 * @author cmiracle@163.com
 * @since 0.1.0
 */
@Component
@RequiredArgsConstructor
public final class ManifestDocumentMapper {

    private final ObjectMapper objectMapper;

    /**
     * 将 JSON 树转换为供领域端口校验的通用数据树。
     *
     * @param document 原始 JSON 树
     * @return Map、List 和 JSON 标量组成的数据树；JSON null 返回 null
     */
    public Object toValidationTree(JsonNode document) {
        if (document == null || document.isNull()) {
            return null;
        }
        return objectMapper.convertValue(document, Object.class);
    }

    /**
     * 将已经通过 Manifest Schema 校验的文档转换为领域对象。
     *
     * @param document 原始 Manifest JSON 树
     * @return 领域 Manifest
     * @throws IllegalArgumentException 文档无法映射为完整领域对象时抛出
     */
    public CapabilityManifest toDomain(JsonNode document) {
        if (document == null || !document.isObject()) {
            throw new IllegalArgumentException("Manifest 根节点必须是 JSON 对象");
        }

        ObjectNode normalized = ((ObjectNode) document).deepCopy();
        normalizeInvocation(normalized.at("/spec/invocation"));
        normalizeOutput(normalized.at("/spec/output"));

        try {
            return objectMapper.readerFor(CapabilityManifest.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(normalized);
        } catch (IOException e) {
            throw new IllegalArgumentException("Manifest 字段无法映射到领域模型", e);
        }
    }

    private static void normalizeInvocation(JsonNode invocationNode) {
        if (!(invocationNode instanceof ObjectNode invocation)) {
            return;
        }

        JsonNode argumentsNode = invocation.get("arguments");
        if (argumentsNode instanceof ArrayNode arguments) {
            for (JsonNode argumentNode : arguments) {
                if (argumentNode instanceof ObjectNode argument) {
                    rename(argument, "value", "constantValue");
                    normalizeFieldBindings(argument.get("object"));
                    rename(argument, "object", "objectBindings");
                }
            }
        }

        normalizeFieldBindings(invocation.get("attachments"));
    }

    private static void normalizeOutput(JsonNode outputNode) {
        if (outputNode instanceof ObjectNode output) {
            rename(output, "projection", "projections");
        }
    }

    private static void normalizeFieldBindings(JsonNode bindingsNode) {
        if (!(bindingsNode instanceof ObjectNode bindings)) {
            return;
        }
        bindings.elements().forEachRemaining(binding -> {
            if (binding instanceof ObjectNode objectBinding) {
                rename(objectBinding, "value", "constantValue");
            }
        });
    }

    private static void rename(ObjectNode node, String externalName, String domainName) {
        JsonNode value = node.remove(externalName);
        if (value != null) {
            node.set(domainName, value);
        }
    }
}
