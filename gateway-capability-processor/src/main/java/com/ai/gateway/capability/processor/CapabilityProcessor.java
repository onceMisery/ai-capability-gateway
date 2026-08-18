package com.ai.gateway.capability.processor;

import com.ai.gateway.capability.annotation.CapArg;
import com.ai.gateway.capability.annotation.CapComposite;
import com.ai.gateway.capability.annotation.CapFieldBinding;
import com.ai.gateway.capability.annotation.CapInput;
import com.ai.gateway.capability.annotation.CapOutput;
import com.ai.gateway.capability.annotation.CapProjection;
import com.ai.gateway.capability.annotation.CapRedaction;
import com.ai.gateway.capability.annotation.Capability;
import com.ai.gateway.capability.annotation.CapabilityArgumentSource;
import com.ai.gateway.capability.annotation.CapabilityGroup;
import com.ai.gateway.capability.annotation.CapabilityOutputMode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.source.doctree.DocCommentTree;
import com.sun.source.doctree.DocTree;
import com.sun.source.doctree.ParamTree;
import com.sun.source.util.DocTrees;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 将显式能力注解转换为稳定、可审查的编译期 JSON 描述符。
 *
 * <p>处理器只读取当前编译单元，不加载业务类，也不访问网络或控制面。</p>
 */
@SupportedAnnotationTypes("com.ai.gateway.capability.annotation.Capability")
@SupportedSourceVersion(SourceVersion.RELEASE_17)
public final class CapabilityProcessor extends AbstractProcessor {

    static final String DESCRIPTOR_PATH = "META-INF/ai-gateway/capabilities.json";
    private static final String DESCRIPTOR_VERSION = "1.0";
    private static final String GENERATOR_VERSION = "0.1.0";
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Pattern ID_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9.-]*$");
    private static final Pattern SEMVER_PATTERN = Pattern.compile(
            "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)"
                    + "(?:-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?"
                    + "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$");
    private static final Set<String> RESERVED_PATH_SEGMENTS = Set.of(
            "class", "@type", "@class", "proto", "__proto__", "constructor", "prototype");

    private final Map<String, CapabilityDescriptor> descriptors = new TreeMap<>();
    private final Map<String, String> annotatedMethods = new TreeMap<>();
    private Elements elements;
    private Types types;
    private DocTrees docTrees;
    private Filer filer;
    private Messager messager;
    private boolean hasErrors;
    private boolean descriptorWritten;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        this.elements = processingEnv.getElementUtils();
        this.types = processingEnv.getTypeUtils();
        this.docTrees = DocTrees.instance(processingEnv);
        this.filer = processingEnv.getFiler();
        this.messager = processingEnv.getMessager();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (!roundEnv.processingOver()) {
            for (Element element : roundEnv.getElementsAnnotatedWith(Capability.class)) {
                collect(element);
            }
        } else if (!descriptorWritten
                && !hasErrors
                && !roundEnv.errorRaised()
                && !descriptors.isEmpty()) {
            writeDescriptor();
        }
        return true;
    }

    private void collect(Element element) {
        if (element.getKind() != ElementKind.METHOD) {
            error(element, "@Capability 只能标注接口方法");
            return;
        }

        ExecutableElement method = (ExecutableElement) element;
        if (!(method.getEnclosingElement() instanceof TypeElement owner)
                || owner.getKind() != ElementKind.INTERFACE) {
            error(method, "@Capability 必须标注在接口方法上");
            return;
        }

        CapabilityGroup group = owner.getAnnotation(CapabilityGroup.class);
        if (group == null) {
            error(owner, "能力接口必须声明 @CapabilityGroup");
            return;
        }

        Capability capability = method.getAnnotation(Capability.class);
        String id = Objects.requireNonNull(capability).id().trim();
        if (!ID_PATTERN.matcher(id).matches()) {
            error(method, "能力 id 格式非法: " + id);
            return;
        }
        String prefix = group.idPrefix().trim();
        if (prefix.isEmpty() || !(id.equals(prefix) || id.startsWith(prefix + "."))) {
            error(method, "能力 id 必须位于分组前缀 '" + prefix + "' 下: " + id);
            return;
        }
        if (!SEMVER_PATTERN.matcher(capability.version().trim()).matches()) {
            error(method, "能力 version 必须符合 SemVer: " + capability.version());
            return;
        }
        if (capability.policyRef().isBlank()) {
            error(method, "能力 policyRef 不能为空");
            return;
        }
        if (descriptors.containsKey(id)) {
            error(method, "当前编译模块存在重复能力 id: " + id);
            return;
        }

        String interfaceName = elements.getBinaryName(owner).toString();
        String methodKey = interfaceName + "#" + method.getSimpleName();
        String existingCapability = annotatedMethods.putIfAbsent(methodKey, id);
        if (existingCapability != null) {
            error(method, "能力方法不支持重载: " + methodKey
                    + "，已由能力 " + existingCapability + " 使用");
            return;
        }

        String description = capability.description().trim();
        if (description.isEmpty()) {
            description = extractSummary(method);
        }
        if (description.isEmpty()) {
            error(method, "能力 description 和方法 Javadoc 不能同时为空");
            return;
        }

        List<ArgumentDescriptor> arguments = collectArguments(method);
        OutputDescriptor output = collectOutput(method);
        if (arguments == null || output == null) {
            return;
        }

        CapInput input = method.getAnnotation(CapInput.class);
        String inputSchemaResource = input == null ? "" : input.schemaResource().trim();
        if (input != null && inputSchemaResource.isEmpty()) {
            error(method, "@CapInput.schemaResource 不能为空");
            return;
        }
        if (inputSchemaResource.isEmpty() && requiresExplicitInputSchema(arguments)) {
            error(method, "复合或非标量 MODEL 输入必须声明 @CapInput.schemaResource");
            return;
        }
        String displayName = capability.displayName().isBlank()
                ? id : capability.displayName().trim();

        descriptors.put(id, new CapabilityDescriptor(
                id,
                capability.version().trim(),
                capability.risk().name(),
                capability.policyRef().trim(),
                displayName,
                description,
                group.protocol().name(),
                interfaceName,
                method.getSimpleName().toString(),
                inputSchemaResource,
                List.copyOf(arguments),
                output));
    }

    private List<ArgumentDescriptor> collectArguments(ExecutableElement method) {
        List<ArgumentDescriptor> result = new ArrayList<>();
        List<? extends VariableElement> parameters = method.getParameters();
        for (int position = 0; position < parameters.size(); position++) {
            VariableElement parameter = parameters.get(position);
            CapArg simple = parameter.getAnnotation(CapArg.class);
            CapComposite composite = parameter.getAnnotation(CapComposite.class);
            if ((simple == null) == (composite == null)) {
                error(parameter, "每个参数必须且只能声明 @CapArg 或 @CapComposite");
                return null;
            }

            String protocolType = types.erasure(parameter.asType()).toString();
            String jsonType = jsonType(parameter.asType());
            String parameterDescription = extractParameterDescription(
                    method, parameter.getSimpleName().toString());
            if (simple != null) {
                String name = simple.name().isBlank()
                        ? parameter.getSimpleName().toString() : simple.name().trim();
                String sourcePath = normalizedSourcePath(simple.source(), simple.sourcePath(), name);
                if (validateBinding(parameter, simple.source(), sourcePath,
                        simple.constantValueJson())) {
                    return null;
                }
                result.add(ArgumentDescriptor.simple(
                        position, name, parameterDescription, protocolType, jsonType,
                        simple.source().name(),
                        sourcePath, simple.converter().trim(), simple.constantValueJson().trim()));
            } else {
                String name = composite.name().isBlank()
                        ? parameter.getSimpleName().toString() : composite.name().trim();
                List<FieldDescriptor> fields = collectCompositeFields(parameter, composite);
                if (fields == null) {
                    return null;
                }
                result.add(ArgumentDescriptor.composite(
                        position, name, parameterDescription, protocolType, jsonType, fields));
            }
        }
        return result;
    }

    private List<FieldDescriptor> collectCompositeFields(
            VariableElement parameter, CapComposite composite) {
        if (composite.value().length == 0) {
            error(parameter, "@CapComposite 至少需要一个字段绑定");
            return null;
        }
        Set<String> targets = new LinkedHashSet<>();
        List<FieldDescriptor> fields = new ArrayList<>();
        for (CapFieldBinding field : composite.value()) {
            String targetPath = field.targetPath().trim();
            if (isNotJsonPointer(targetPath) || containsReservedSegment(targetPath)) {
                error(parameter, "复合绑定 targetPath 非法或包含保留字段: " + targetPath);
                return null;
            }
            if (!targets.add(targetPath)) {
                error(parameter, "复合绑定 targetPath 重复: " + targetPath);
                return null;
            }
            String sourcePath = normalizedSourcePath(field.source(), field.sourcePath(), "");
            if (validateBinding(parameter, field.source(), sourcePath,
                    field.constantValueJson())) {
                return null;
            }
            fields.add(new FieldDescriptor(
                    targetPath,
                    field.source().name(),
                    sourcePath,
                    field.converter().trim(),
                    field.constantValueJson().trim()));
        }
        return List.copyOf(fields);
    }

    private OutputDescriptor collectOutput(ExecutableElement method) {
        CapOutput output = method.getAnnotation(CapOutput.class);
        if (output == null) {
            error(method, "能力方法必须声明 @CapOutput");
            return null;
        }
        if (output.schemaResource().isBlank()) {
            error(method, "@CapOutput.schemaResource 不能为空");
            return null;
        }
        if (output.maxBytes() <= 0) {
            error(method, "@CapOutput.maxBytes 必须大于 0");
            return null;
        }
        if (output.mode() == CapabilityOutputMode.ENVELOPE
                && output.envelopeProfile().isBlank()) {
            error(method, "ENVELOPE 输出必须声明 envelopeProfile");
            return null;
        }
        if (output.mode() == CapabilityOutputMode.DIRECT
                && !output.envelopeProfile().isBlank()) {
            error(method, "DIRECT 输出不能声明 envelopeProfile");
            return null;
        }

        List<ProjectionDescriptor> projections = new ArrayList<>();
        Set<String> targets = new HashSet<>();
        for (CapProjection projection : output.projection()) {
            String from = projection.from().trim();
            String to = projection.to().trim();
            if (isNotJsonPointer(from) || isNotJsonPointer(to)) {
                error(method, "projection.from/to 必须是 JSON Pointer: " + from + " -> " + to);
                return null;
            }
            if (!targets.add(to)) {
                error(method, "projection.to 不能重复: " + to);
                return null;
            }
            projections.add(new ProjectionDescriptor(from, to));
        }

        List<RedactionDescriptor> redactions = new ArrayList<>();
        for (CapRedaction redaction : output.redactions()) {
            String path = redaction.path().trim();
            if (isNotJsonPointer(path)) {
                error(method, "redaction.path 必须是 JSON Pointer: " + path);
                return null;
            }
            redactions.add(new RedactionDescriptor(path, redaction.method().name()));
        }

        return new OutputDescriptor(
                output.mode().name(),
                output.envelopeProfile().trim(),
                output.schemaResource().trim(),
                output.maxBytes(),
                List.copyOf(projections),
                List.copyOf(redactions));
    }

    private boolean validateBinding(
            Element element,
            CapabilityArgumentSource source,
            String sourcePath,
            String constantValueJson) {
        if (source == CapabilityArgumentSource.CONSTANT) {
            if (constantValueJson.isBlank()) {
                error(element, "CONSTANT 来源必须声明 constantValueJson");
                return true;
            }
            if (!sourcePath.isEmpty()) {
                error(element, "CONSTANT 来源不能声明 sourcePath");
                return true;
            }
            return !validateConstantJson(element, constantValueJson);
        }
        if (!constantValueJson.isBlank()) {
            error(element, source + " 来源不能声明 constantValueJson");
            return true;
        }
        if (isNotJsonPointer(sourcePath)) {
            error(element, source + " 来源必须声明合法 sourcePath: " + sourcePath);
            return true;
        }
        return false;
    }

    private static String normalizedSourcePath(
            CapabilityArgumentSource source, String configuredPath, String name) {
        String path = configuredPath.trim();
        if (path.isEmpty() && source == CapabilityArgumentSource.MODEL && !name.isEmpty()) {
            return "/" + name.replace("~", "~0").replace("/", "~1");
        }
        return path;
    }

    private String extractSummary(ExecutableElement method) {
        DocCommentTree doc = docTrees.getDocCommentTree(method);
        if (doc == null) {
            return "";
        }
        return normalizeDocTrees(doc.getFirstSentence());
    }

    private String extractParameterDescription(ExecutableElement method, String parameterName) {
        DocCommentTree doc = docTrees.getDocCommentTree(method);
        if (doc == null) {
            return "";
        }
        return doc.getBlockTags().stream()
                .filter(ParamTree.class::isInstance)
                .map(ParamTree.class::cast)
                .filter(param -> !param.isTypeParameter()
                        && param.getName().getName().contentEquals(parameterName))
                .map(ParamTree::getDescription)
                .map(CapabilityProcessor::normalizeDocTrees)
                .findFirst()
                .orElse("");
    }

    private static String normalizeDocTrees(List<? extends DocTree> trees) {
        return trees.stream()
                .map(DocTree::toString)
                .collect(Collectors.joining())
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static boolean requiresExplicitInputSchema(List<ArgumentDescriptor> arguments) {
        for (ArgumentDescriptor argument : arguments) {
            if (argument.composite()) {
                if (argument.fields().stream()
                        .anyMatch(field -> "MODEL".equals(field.source()))) {
                    return true;
                }
            } else if ("MODEL".equals(argument.source())
                    && !("string".equals(argument.jsonType())
                    || "integer".equals(argument.jsonType())
                    || "number".equals(argument.jsonType())
                    || "boolean".equals(argument.jsonType()))) {
                return true;
            }
        }
        return false;
    }

    private boolean validateConstantJson(Element element, String constantValueJson) {
        try {
            JsonNode value = JSON_MAPPER.readTree(constantValueJson);
            if (value == null || !(value.isTextual() || value.isNumber() || value.isBoolean())) {
                error(element, "constantValueJson 必须是字符串、数字或布尔 JSON 标量");
                return false;
            }
            return true;
        } catch (JsonProcessingException e) {
            error(element, "constantValueJson 不是合法的单个 JSON 值: "
                    + e.getOriginalMessage());
            return false;
        }
    }

    private String jsonType(TypeMirror type) {
        TypeKind kind = type.getKind();
        if (kind == TypeKind.BOOLEAN) {
            return "boolean";
        }
        if (kind == TypeKind.BYTE || kind == TypeKind.SHORT
                || kind == TypeKind.INT || kind == TypeKind.LONG) {
            return "integer";
        }
        if (kind == TypeKind.FLOAT || kind == TypeKind.DOUBLE) {
            return "number";
        }
        if (kind == TypeKind.ARRAY) {
            ArrayType array = (ArrayType) type;
            return array.getComponentType().getKind() == TypeKind.BYTE ? "string" : "array";
        }

        String erased = types.erasure(type).toString();
        if (erased.equals("java.lang.Boolean")) {
            return "boolean";
        }
        if (Set.of("java.lang.Byte", "java.lang.Short", "java.lang.Integer",
                "java.lang.Long", "java.math.BigInteger").contains(erased)) {
            return "integer";
        }
        if (Set.of("java.lang.Float", "java.lang.Double",
                "java.math.BigDecimal").contains(erased)) {
            return "number";
        }
        if (erased.equals("java.lang.String") || erased.equals("java.lang.Character")
                || erased.startsWith("java.time.") || erased.startsWith("java.util.UUID")) {
            return "string";
        }
        if (erased.equals("java.util.List") || erased.equals("java.util.Set")
                || erased.equals("java.util.Collection")) {
            return "array";
        }
        return "object";
    }

    private static boolean isNotJsonPointer(String value) {
        return value == null || !value.startsWith("/") || value.contains("//");
    }

    private static boolean containsReservedSegment(String pointer) {
        for (String rawSegment : pointer.substring(1).split("/")) {
            String segment = rawSegment.replace("~1", "/").replace("~0", "~")
                    .toLowerCase(Locale.ROOT);
            if (RESERVED_PATH_SEGMENTS.contains(segment)) {
                return true;
            }
        }
        return false;
    }

    private void writeDescriptor() {
        descriptorWritten = true;
        try {
            FileObject resource = filer.createResource(
                    StandardLocation.CLASS_OUTPUT, "", DESCRIPTOR_PATH);
            // 描述符可能包含中文说明，必须显式使用 UTF-8，不能依赖操作系统默认编码。
            try (Writer writer = new OutputStreamWriter(
                    resource.openOutputStream(), StandardCharsets.UTF_8)) {
                writer.write(toJson(descriptors.values().stream()
                        .sorted(Comparator.comparing(CapabilityDescriptor::id))
                        .toList()));
            }
        } catch (IOException e) {
            error(null, "写入能力描述符失败: " + e.getMessage());
        }
    }

    private static String toJson(List<CapabilityDescriptor> capabilities) {
        StringBuilder json = new StringBuilder(4096);
        json.append("{\n")
                .append("  \"descriptorVersion\": \"").append(DESCRIPTOR_VERSION).append("\",\n")
                .append("  \"generatorVersion\": \"").append(GENERATOR_VERSION).append("\",\n")
                .append("  \"capabilities\": [");
        for (int i = 0; i < capabilities.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            appendCapability(json, capabilities.get(i));
        }
        return json.append("\n  ]\n}\n").toString();
    }

    private static void appendCapability(StringBuilder json, CapabilityDescriptor value) {
        json.append("\n    {");
        appendStringField(json, "id", value.id(), true, 6);
        appendStringField(json, "version", value.version(), true, 6);
        appendStringField(json, "risk", value.risk(), true, 6);
        appendStringField(json, "policyRef", value.policyRef(), true, 6);
        appendStringField(json, "displayName", value.displayName(), true, 6);
        appendStringField(json, "description", value.description(), true, 6);
        appendStringField(json, "protocol", value.protocol(), true, 6);
        appendStringField(json, "interfaceName", value.interfaceName(), true, 6);
        appendStringField(json, "method", value.method(), true, 6);
        appendStringField(json, "inputSchemaResource", value.inputSchemaResource(), true, 6);
        json.append("\n      \"arguments\": [");
        for (int i = 0; i < value.arguments().size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            appendArgument(json, value.arguments().get(i));
        }
        json.append("\n      ],\n      \"output\": ");
        appendOutput(json, value.output());
        json.append("\n    }");
    }

    private static void appendArgument(StringBuilder json, ArgumentDescriptor value) {
        json.append("\n        {\n")
                .append("          \"position\": ").append(value.position()).append(',');
        appendStringField(json, "name", value.name(), true, 10);
        appendStringField(json, "description", value.description(), true, 10);
        appendStringField(json, "protocolType", value.protocolType(), true, 10);
        appendStringField(json, "jsonType", value.jsonType(), true, 10);
        if (!value.composite()) {
            appendStringField(json, "source", value.source(), true, 10);
            appendStringField(json, "sourcePath", value.sourcePath(), true, 10);
            appendStringField(json, "converter", value.converter(), true, 10);
            appendStringField(json, "constantValueJson", value.constantValueJson(), false, 10);
        } else {
            json.append("\n          \"object\": [");
            for (int i = 0; i < value.fields().size(); i++) {
                if (i > 0) {
                    json.append(',');
                }
                FieldDescriptor field = value.fields().get(i);
                json.append("\n            {");
                appendStringField(json, "targetPath", field.targetPath(), true, 14);
                appendStringField(json, "source", field.source(), true, 14);
                appendStringField(json, "sourcePath", field.sourcePath(), true, 14);
                appendStringField(json, "converter", field.converter(), true, 14);
                appendStringField(json, "constantValueJson", field.constantValueJson(), false, 14);
                json.append("\n            }");
            }
            json.append("\n          ]");
        }
        json.append("\n        }");
    }

    private static void appendOutput(StringBuilder json, OutputDescriptor value) {
        json.append('{');
        appendStringField(json, "mode", value.mode(), true, 8);
        appendStringField(json, "envelopeProfile", value.envelopeProfile(), true, 8);
        appendStringField(json, "schemaResource", value.schemaResource(), true, 8);
        json.append("\n        \"maxBytes\": ").append(value.maxBytes()).append(',');
        json.append("\n        \"projection\": [");
        for (int i = 0; i < value.projections().size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            ProjectionDescriptor mapping = value.projections().get(i);
            json.append("\n          {");
            appendStringField(json, "from", mapping.from(), true, 12);
            appendStringField(json, "to", mapping.to(), false, 12);
            json.append("\n          }");
        }
        json.append("\n        ],\n        \"redactions\": [");
        for (int i = 0; i < value.redactions().size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            RedactionDescriptor redaction = value.redactions().get(i);
            json.append("\n          {");
            appendStringField(json, "path", redaction.path(), true, 12);
            appendStringField(json, "method", redaction.method(), false, 12);
            json.append("\n          }");
        }
        json.append("\n        ]\n      }");
    }

    private static void appendStringField(
            StringBuilder json, String name, String value, boolean comma, int indent) {
        json.append('\n').append(" ".repeat(indent))
                .append('\"').append(name).append("\": \"")
                .append(escapeJson(value)).append('\"');
        if (comma) {
            json.append(',');
        }
    }

    static String escapeJson(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (c < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.toString();
    }

    private void error(Element element, String message) {
        hasErrors = true;
        if (element == null) {
            messager.printMessage(Diagnostic.Kind.ERROR, message);
        } else {
            messager.printMessage(Diagnostic.Kind.ERROR, message, element);
        }
    }

    private record CapabilityDescriptor(
            String id,
            String version,
            String risk,
            String policyRef,
            String displayName,
            String description,
            String protocol,
            String interfaceName,
            String method,
            String inputSchemaResource,
            List<ArgumentDescriptor> arguments,
            OutputDescriptor output) {
    }

    private record ArgumentDescriptor(
            int position,
            String name,
            String description,
            String protocolType,
            String jsonType,
            String source,
            String sourcePath,
            String converter,
            String constantValueJson,
            List<FieldDescriptor> fields) {

        static ArgumentDescriptor simple(
                int position, String name, String description, String protocolType, String jsonType,
                String source, String sourcePath, String converter, String constantValueJson) {
            return new ArgumentDescriptor(position, name, description, protocolType, jsonType, source,
                    sourcePath, converter, constantValueJson, List.of());
        }

        static ArgumentDescriptor composite(
                int position, String name, String description, String protocolType, String jsonType,
                List<FieldDescriptor> fields) {
            return new ArgumentDescriptor(position, name, description, protocolType, jsonType,
                    "", "", "", "", List.copyOf(fields));
        }

        boolean composite() {
            return !fields.isEmpty();
        }
    }

    private record FieldDescriptor(
            String targetPath,
            String source,
            String sourcePath,
            String converter,
            String constantValueJson) {
    }

    private record OutputDescriptor(
            String mode,
            String envelopeProfile,
            String schemaResource,
            int maxBytes,
            List<ProjectionDescriptor> projections,
            List<RedactionDescriptor> redactions) {
    }

    private record ProjectionDescriptor(String from, String to) {
    }

    private record RedactionDescriptor(String path, String method) {
    }
}
