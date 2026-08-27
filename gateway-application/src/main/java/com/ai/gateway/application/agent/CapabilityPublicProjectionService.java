package com.ai.gateway.application.agent;

import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.ArgumentBinding;
import com.ai.gateway.domain.model.ArgumentSource;
import com.ai.gateway.domain.service.InstructionInjectionDetector;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Builds bounded model-facing capability data from untrusted Manifest content. */
public final class CapabilityPublicProjectionService {

    private static final int MAX_DISPLAY_NAME = 80;
    private static final int MAX_PURPOSE = 240;
    private static final int MAX_FIELD_DESCRIPTION = 160;
    private static final int MAX_PROPERTIES = 32;
    private static final int MAX_PUBLIC_SCHEMA_BYTES = 16 * 1024;
    private static final int MAX_EXAMPLE = 120;
    private static final Set<String> COMBINATORS = Set.of(
            "oneOf", "anyOf", "allOf", "not", "if", "then", "else",
            "dependentSchemas", "patternProperties", "unevaluatedProperties");
    private static final Set<String> COMPACT_KEYS = Set.of(
            "type", "enum", "const", "format", "minimum", "maximum",
            "exclusiveMinimum", "exclusiveMaximum", "minLength", "maxLength",
            "pattern", "minItems", "maxItems", "uniqueItems", "default");
    /**
     * 注入检测委派给领域服务。
     *
     * <p>出站投影（清单叙述字段）与 A2A 入站 Task 文本必须使用同一份模式列表：
     * 两侧各自维护会产生「出站拦得住、入站拦不住」的偏差，而这种偏差无法被任何单侧测试发现。</p>
     */
    private static final InstructionInjectionDetector INJECTION_DETECTOR =
            InstructionInjectionDetector.builtIn();

    public Optional<Projection> project(CapabilityManifest manifest) {
        if (manifest == null || containsUnsafeContent(manifest)) {
            return Optional.empty();
        }
        Map<String, Object> modelSchema = stripTrustedFields(manifest);
        Map<String, Object> publicSchema = sanitizeSchema(modelSchema, 0);
        if (estimatedUtf8Bytes(publicSchema) > MAX_PUBLIC_SCHEMA_BYTES) {
            return Optional.of(new Projection(
                    sanitize(manifest.spec().displayName(), MAX_DISPLAY_NAME),
                    sanitize(manifest.spec().description(), MAX_PURPOSE),
                    SchemaClass.COMPLEX,
                    compactContract(modelSchema),
                    Map.of()));
        }
        return Optional.of(new Projection(
                sanitize(manifest.spec().displayName(), MAX_DISPLAY_NAME),
                sanitize(manifest.spec().description(), MAX_PURPOSE),
                classify(modelSchema),
                compactContract(modelSchema),
                publicSchema));
    }

    /**
     * 计算「因来源非 MODEL 而必须从模型可见 Schema 中剥离」的根级字段名。
     *
     * <p>与 {@link #stripTrustedFields(CapabilityManifest)} 共用同一份判定，
     * 保证管理面诊断展示的剥离清单与模型实际看到的投影严格一致——若两者各自实现，
     * 诊断结论就可能与真实模型视图不符，从而给出错误的清单修复建议。</p>
     *
     * @param manifest 能力清单，允许为 {@code null}
     * @return 被剥离的根级字段名集合；无剥离时为空集合，永不为 {@code null}
     */
    public Set<String> trustedFieldNames(CapabilityManifest manifest) {
        if (manifest == null) {
            return Set.of();
        }
        Set<String> hiddenRootFields = new java.util.LinkedHashSet<>();
        for (ArgumentBinding binding : manifest.spec().invocation().arguments()) {
            if (!binding.isComposite() && binding.source() != null
                    && binding.source() != ArgumentSource.MODEL) {
                hiddenRootFields.add(binding.name());
            }
            if (binding.isComposite()) {
                binding.objectBindings().forEach((targetPath, fieldBinding) -> {
                    if (fieldBinding.source() != ArgumentSource.MODEL) {
                        String root = firstPointerSegment(targetPath);
                        if (!root.isBlank()) {
                            hiddenRootFields.add(root);
                        }
                    }
                });
            }
        }
        return hiddenRootFields;
    }

    /**
     * 计算「可以安全出现在 Agent 侧可见面上的正向示例」。
     *
     * <p>这是一个<b>新增</b>的公开投影视角，而不是对 {@link Projection} 的改造：示例只被 AgentCard
     * 这类「域粒度描述面」需要，把它塞进 {@code Projection} 会波及所有既有构造点，
     * 也会让 MCP 工具投影凭空多出一份它并不使用的字段（开闭原则）。</p>
     *
     * <p>示例与 {@link #project(CapabilityManifest)} 共用同一道注入检测：命中注入模式的清单一律
     * 返回空列表，而不是「去掉可疑那条、留下其余」——同一份清单里既然已有人尝试注入，
     * 它剩余的自然语言内容也不再具备可信度。</p>
     *
     * @param manifest     能力清单，允许为 {@code null}
     * @param maxExamples  返回条数上限，非正数时返回空列表
     * @return 已归一化、去重、截断的正向示例；不可用时为空列表，永不为 {@code null}
     */
    public List<String> publicExamples(CapabilityManifest manifest, int maxExamples) {
        if (manifest == null || maxExamples <= 0 || containsUnsafeContent(manifest)) {
            return List.of();
        }
        java.util.LinkedHashSet<String> examples = new java.util.LinkedHashSet<>();
        for (String candidate : manifest.spec().examples().positive()) {
            String sanitized = sanitize(candidate, MAX_EXAMPLE);
            if (!sanitized.isEmpty()) {
                examples.add(sanitized);
            }
            if (examples.size() >= maxExamples) {
                break;
            }
        }
        return List.copyOf(examples);
    }

    private Map<String, Object> stripTrustedFields(CapabilityManifest manifest) {        Set<String> hiddenRootFields = trustedFieldNames(manifest);
        if (hiddenRootFields.isEmpty()) {
            return manifest.spec().inputSchema();
        }

        Map<String, Object> copy = mutableMap(manifest.spec().inputSchema(), 0);
        if (copy.get("properties") instanceof Map<?, ?> rawProperties) {
            @SuppressWarnings("unchecked")
            Map<String, Object> properties = (Map<String, Object>) rawProperties;
            hiddenRootFields.forEach(properties::remove);
        }
        if (copy.get("required") instanceof List<?> required) {
            copy.put("required", required.stream()
                    .filter(item -> !hiddenRootFields.contains(String.valueOf(item)))
                    .toList());
        }
        return copy;
    }

    private static String firstPointerSegment(String pointer) {
        if (pointer == null || pointer.isBlank()) {
            return "";
        }
        String value = pointer.startsWith("/") ? pointer.substring(1) : pointer;
        int slash = value.indexOf('/');
        String segment = slash >= 0 ? value.substring(0, slash) : value;
        return segment.replace("~1", "/").replace("~0", "~");
    }

    private Map<String, Object> mutableMap(Map<String, Object> source, int depth) {
        if (depth > 16) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, mutableValue(value, depth + 1)));
        return copy;
    }

    private Object mutableValue(Object value, int depth) {
        if (depth > 16) {
            return Map.of();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, child) -> copy.put(String.valueOf(key),
                    mutableValue(child, depth + 1)));
            return copy;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> copy = new ArrayList<>();
            iterable.forEach(item -> copy.add(mutableValue(item, depth + 1)));
            return copy;
        }
        return value;
    }

    private boolean containsUnsafeContent(CapabilityManifest manifest) {
        List<String> content = new ArrayList<>();
        content.add(manifest.spec().displayName());
        content.add(manifest.spec().description());
        content.addAll(manifest.spec().examples().positive());
        content.addAll(manifest.spec().examples().negative());
        content.addAll(manifest.spec().examples().synonyms());
        collectSchemaNarrative(manifest.spec().inputSchema(), content);
        return content.stream().anyMatch(this::looksLikeInstruction);
    }

    private void collectSchemaNarrative(Object node, List<String> content) {
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                Object value = entry.getValue();
                if (("description".equals(key) || "title".equals(key) || "$comment".equals(key))
                        && value instanceof String text) {
                    content.add(text);
                }
                collectSchemaNarrative(value, content);
            }
        } else if (node instanceof Iterable<?> iterable) {
            iterable.forEach(item -> collectSchemaNarrative(item, content));
        }
    }

    private boolean looksLikeInstruction(String value) {
        return INJECTION_DETECTOR.detects(value);
    }

    private SchemaClass classify(Map<String, Object> schema) {
        Complexity complexity = inspect(schema, 0);
        if (complexity.hasCombinator() || complexity.depth() > 5
                || complexity.properties() > MAX_PROPERTIES) {
            return SchemaClass.COMPLEX;
        }
        if (complexity.depth() <= 2 && complexity.properties() <= 8) {
            return SchemaClass.SIMPLE;
        }
        return SchemaClass.STANDARD;
    }

    private Complexity inspect(Object node, int depth) {
        if (node instanceof Map<?, ?> map) {
            int maxDepth = depth;
            int properties = 0;
            boolean combinator = false;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (COMBINATORS.contains(key)) {
                    combinator = true;
                }
                if ("properties".equals(key) && entry.getValue() instanceof Map<?, ?> propertyMap) {
                    properties += propertyMap.size();
                }
                Complexity child = inspect(entry.getValue(), depth + 1);
                maxDepth = Math.max(maxDepth, child.depth());
                properties += child.properties();
                combinator |= child.hasCombinator();
            }
            return new Complexity(maxDepth, properties, combinator);
        }
        if (node instanceof Iterable<?> iterable) {
            int maxDepth = depth;
            int properties = 0;
            boolean combinator = false;
            for (Object item : iterable) {
                Complexity child = inspect(item, depth + 1);
                maxDepth = Math.max(maxDepth, child.depth());
                properties += child.properties();
                combinator |= child.hasCombinator();
            }
            return new Complexity(maxDepth, properties, combinator);
        }
        return new Complexity(depth, 0, false);
    }

    private Map<String, Object> compactContract(Map<String, Object> schema) {
        Map<String, Object> compact = new LinkedHashMap<>();
        compact.put("type", "object");
        copyRequired(schema, compact);
        if (schema.get("additionalProperties") instanceof Boolean additionalProperties) {
            compact.put("additionalProperties", additionalProperties);
        }
        Object propertiesNode = schema.get("properties");
        if (propertiesNode instanceof Map<?, ?> properties) {
            Map<String, Object> compactProperties = new LinkedHashMap<>();
            int count = 0;
            for (Map.Entry<?, ?> entry : properties.entrySet()) {
                if (count++ >= MAX_PROPERTIES) {
                    break;
                }
                compactProperties.put(
                        sanitize(String.valueOf(entry.getKey()), 128),
                        compactProperty(entry.getValue(), 0));
            }
            compact.put("properties", Map.copyOf(compactProperties));
        }
        return Map.copyOf(compact);
    }

    private Map<String, Object> compactProperty(Object node, int depth) {
        if (!(node instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> compact = new LinkedHashMap<>();
        for (String key : COMPACT_KEYS) {
            if (map.containsKey(key)) {
                compact.put(key, immutableValue(map.get(key), depth + 1));
            }
        }
        if (map.get("description") instanceof String description) {
            compact.put("description", sanitize(description, MAX_FIELD_DESCRIPTION));
        }
        if (depth < 1 && map.get("items") instanceof Map<?, ?> items) {
            compact.put("items", compactProperty(items, depth + 1));
        }
        return Map.copyOf(compact);
    }

    private void copyRequired(Map<String, Object> source, Map<String, Object> target) {
        if (!(source.get("required") instanceof Iterable<?> required)) {
            return;
        }
        List<String> values = new ArrayList<>();
        for (Object item : required) {
            if (item instanceof String value && !value.isBlank() && values.size() < MAX_PROPERTIES) {
                values.add(sanitize(value, 128));
            }
        }
        if (!values.isEmpty()) {
            target.put("required", List.copyOf(values));
        }
    }

    private Map<String, Object> sanitizeSchema(Map<String, Object> schema, int depth) {
        if (depth > 16) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : schema.entrySet()) {
            String key = entry.getKey();
            if ("$comment".equals(key) || "examples".equals(key)) {
                continue;
            }
            Object value = entry.getValue();
            if (("description".equals(key) || "title".equals(key)) && value instanceof String text) {
                result.put(key, sanitize(text, MAX_FIELD_DESCRIPTION));
            } else {
                result.put(key, immutableValue(value, depth + 1));
            }
        }
        return Map.copyOf(result);
    }

    private Object immutableValue(Object value, int depth) {
        if (depth > 16) {
            return Map.of();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, child) -> copy.put(
                    sanitize(String.valueOf(key), 128),
                    ("description".equals(String.valueOf(key)) && child instanceof String text)
                            ? sanitize(text, MAX_FIELD_DESCRIPTION)
                            : immutableValue(child, depth + 1)));
            return Map.copyOf(copy);
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> copy = new ArrayList<>();
            iterable.forEach(item -> copy.add(immutableValue(item, depth + 1)));
            return List.copyOf(copy);
        }
        if (value instanceof String text) {
            return sanitize(text, 512);
        }
        return value;
    }

    private static String sanitize(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replaceAll("[\\p{Cc}\\p{Cf}]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength).trim();
    }

    private static long estimatedUtf8Bytes(Object value) {
        return String.valueOf(value).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }

    public enum SchemaClass { SIMPLE, STANDARD, COMPLEX }

    public record Projection(
            String displayName,
            String purpose,
            SchemaClass schemaClass,
            Map<String, Object> argumentContract,
            Map<String, Object> publicSchema) {
    }

    private record Complexity(int depth, int properties, boolean hasCombinator) {
    }
}
