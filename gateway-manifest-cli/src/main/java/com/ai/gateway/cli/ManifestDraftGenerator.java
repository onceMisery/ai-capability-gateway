package com.ai.gateway.cli;

import com.ai.gateway.cli.GenerationModels.ArgumentDescriptor;
import com.ai.gateway.cli.GenerationModels.CapabilityDescriptor;
import com.ai.gateway.cli.GenerationModels.DescriptorDocument;
import com.ai.gateway.cli.GenerationModels.EnvelopeProfile;
import com.ai.gateway.cli.GenerationModels.EnvironmentProfile;
import com.ai.gateway.cli.GenerationModels.Examples;
import com.ai.gateway.cli.GenerationModels.FieldDescriptor;
import com.ai.gateway.cli.GenerationModels.GovernanceConfig;
import com.ai.gateway.cli.GenerationModels.GovernancePolicy;
import com.ai.gateway.cli.GenerationModels.Owner;
import com.ai.gateway.cli.GenerationModels.ProviderProfile;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 将编译期能力描述符与治理、环境配置合成为可审查的 Manifest Draft。
 */
final class ManifestDraftGenerator {

    private static final String SUPPORTED_DESCRIPTOR_VERSION = "1.0";
    private static final int MAX_DESCRIPTOR_BYTES = 4 * 1024 * 1024;
    private static final int MAX_CONFIG_BYTES = 2 * 1024 * 1024;
    private static final int MAX_SCHEMA_BYTES = 2 * 1024 * 1024;
    private static final int MAX_CAPABILITIES = 1000;

    private final ObjectMapper jsonMapper;
    private final ObjectMapper yamlMapper;
    private final ManifestSchemaValidator schemaValidator;

    ManifestDraftGenerator() {
        this.jsonMapper = new ObjectMapper()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.yamlMapper = new ObjectMapper(new YAMLFactory())
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.schemaValidator = new ManifestSchemaValidator();
    }

    GenerationResult generate(GenerationRequest request) throws IOException {
        Objects.requireNonNull(request, "request must not be null");
        validateRegularFile(request.descriptor(), "descriptor");
        validateRegularFile(request.governance(), "governance");
        validateRegularFile(request.profile(), "profile");
        validateFileSize(request.descriptor(), "descriptor", MAX_DESCRIPTOR_BYTES);
        validateFileSize(request.governance(), "governance", MAX_CONFIG_BYTES);
        validateFileSize(request.profile(), "profile", MAX_CONFIG_BYTES);
        if (!Files.isDirectory(request.schemasRoot())) {
            throw new IOException("schemas directory not found: " + request.schemasRoot());
        }

        DescriptorDocument descriptor = jsonMapper.readValue(
                Files.readString(request.descriptor(), StandardCharsets.UTF_8),
                DescriptorDocument.class);
        GovernanceConfig governance = yamlMapper.readValue(
                Files.readString(request.governance(), StandardCharsets.UTF_8),
                GovernanceConfig.class);
        EnvironmentProfile profile = yamlMapper.readValue(
                Files.readString(request.profile(), StandardCharsets.UTF_8),
                EnvironmentProfile.class);
        validateRootDocuments(descriptor, governance, profile);

        Files.createDirectories(request.outputDirectory());
        Path reportParent = request.report().toAbsolutePath().normalize().getParent();
        if (reportParent != null) {
            Files.createDirectories(reportParent);
        }

        List<String> generated = new ArrayList<>();
        Map<String, List<String>> failures = new LinkedHashMap<>();
        descriptor.capabilities().stream()
                .sorted(Comparator.comparing(CapabilityDescriptor::id))
                .forEach(capability -> generateOne(
                        request, governance, profile, capability, generated, failures));

        writeReport(request.report(), descriptor, generated, failures);
        return new GenerationResult(List.copyOf(generated), Map.copyOf(failures));
    }

    private void generateOne(
            GenerationRequest request,
            GovernanceConfig governance,
            EnvironmentProfile profile,
            CapabilityDescriptor capability,
            List<String> generated,
            Map<String, List<String>> failures) {
        String id = capability == null || capability.id() == null
                ? "<unknown>" : capability.id();
        Path output = null;
        try {
            if (capability != null && hasText(capability.id())) {
                output = resolveOutputPath(request.outputDirectory(), capability.id());
                // 本次生成失败时不能让上一次的同名 Draft 继续冒充最新结果。
                Files.deleteIfExists(output);
            }
            ObjectNode manifest = buildManifest(
                    request.schemasRoot(), governance, profile, capability);
            List<String> validationErrors = schemaValidator.validate(manifest);
            if (!validationErrors.isEmpty()) {
                failures.put(id, validationErrors);
                return;
            }
            if (output == null) {
                throw new IOException("capability id must not be blank");
            }
            writeJsonAtomically(output, manifest);
            generated.add(id);
        } catch (Exception e) {
            failures.put(id, List.of(safeMessage(e)));
        }
    }

    private ObjectNode buildManifest(
            Path schemasRoot,
            GovernanceConfig governance,
            EnvironmentProfile environment,
            CapabilityDescriptor capability) throws IOException {
        requireText(capability.id(), "descriptor capability.id");
        requireText(capability.policyRef(), capability.id() + ".policyRef");
        requireText(capability.interfaceName(), capability.id() + ".interfaceName");
        requireText(capability.method(), capability.id() + ".method");
        if (capability.arguments() == null || capability.output() == null) {
            throw new IOException("descriptor arguments/output must not be null: " + capability.id());
        }

        GovernancePolicy policy = governance.policies().get(capability.policyRef());
        if (policy == null) {
            throw new IOException("governance policy not found: " + capability.policyRef());
        }
        validatePolicy(capability.id(), policy);

        ProviderProfile provider = environment.providers().get(capability.interfaceName());
        if (provider == null) {
            throw new IOException("provider profile not found: " + capability.interfaceName());
        }
        validateProvider(capability, provider);

        ObjectNode root = jsonMapper.createObjectNode();
        root.put("apiVersion", "gateway.ai/v1");
        root.put("kind", "Capability");

        ObjectNode metadata = root.putObject("metadata");
        metadata.put("id", capability.id());
        metadata.put("version", capability.version());
        ObjectNode owner = metadata.putObject("owner");
        owner.put("team", policy.owner().team());
        owner.put("contact", policy.owner().contact());
        addStrings(metadata.putArray("tags"), policy.tags());

        ObjectNode spec = root.putObject("spec");
        spec.put("displayName", capability.displayName());
        spec.put("description", capability.description());
        appendExamples(spec.putObject("examples"), policy.examples());
        spec.put("risk", capability.risk());
        spec.set("inputSchema", buildInputSchema(schemasRoot, capability));
        appendAuthorization(spec, policy);
        spec.set("invocation", buildInvocation(capability, provider));
        spec.set("output", buildOutput(schemasRoot, environment, capability));
        ObjectNode resilience = spec.putObject("resilience");
        resilience.put("timeoutMs", provider.resilience().timeoutMs());
        resilience.put("retries", provider.resilience().retries());
        resilience.put("maxConcurrent", provider.resilience().maxConcurrent());
        return root;
    }

    private ObjectNode buildInputSchema(
            Path schemasRoot, CapabilityDescriptor capability) throws IOException {
        if (hasText(capability.inputSchemaResource())) {
            return readObjectSchema(schemasRoot, capability.inputSchemaResource());
        }

        ObjectNode schema = jsonMapper.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        ObjectNode properties = schema.putObject("properties");
        ArrayNode required = jsonMapper.createArrayNode();

        for (ArgumentDescriptor argument : capability.arguments()) {
            if (argument.composite()) {
                boolean containsModel = argument.object().stream()
                        .anyMatch(field -> "MODEL".equals(field.source()));
                if (containsModel) {
                    throw new IOException("composite MODEL input requires @CapInput schemaResource: "
                            + capability.id() + "#" + argument.name());
                }
                continue;
            }
            if (!"MODEL".equals(argument.source())) {
                continue;
            }
            String propertyName = singlePropertyName(argument.sourcePath());
            ObjectNode property = properties.putObject(propertyName);
            String type = hasText(argument.jsonType()) ? argument.jsonType() : "object";
            property.put("type", type);
            putIfText(property, "description", argument.description());
            if ("string".equals(type)) {
                property.put("maxLength", 4096);
            } else if ("array".equals(type)) {
                property.put("maxItems", 1000);
            }
            required.add(propertyName);
        }
        if (!required.isEmpty()) {
            schema.set("required", required);
        }
        return schema;
    }

    private ObjectNode buildInvocation(
            CapabilityDescriptor capability, ProviderProfile provider) throws IOException {
        ObjectNode invocation = jsonMapper.createObjectNode();
        invocation.put("protocol", capability.protocol());
        if ("DUBBO".equals(capability.protocol())) {
            requireText(provider.registryRef(), capability.interfaceName() + ".registryRef");
            invocation.put("registryRef", provider.registryRef());
        }
        invocation.put("interfaceName", capability.interfaceName());
        if (hasText(provider.group())) {
            invocation.put("group", provider.group());
        }
        invocation.put("version", provider.serviceVersion());
        invocation.put("method", capability.method());
        ArrayNode parameterTypes = invocation.putArray("parameterTypes");
        ArrayNode arguments = invocation.putArray("arguments");
        capability.arguments().stream()
                .sorted(Comparator.comparingInt(ArgumentDescriptor::position))
                .forEach(argument -> {
                    parameterTypes.add(argument.protocolType());
                    arguments.add(buildArgument(argument));
                });
        invocation.put("serialization", provider.serialization());
        return invocation;
    }

    private ObjectNode buildArgument(ArgumentDescriptor argument) {
        ObjectNode node = jsonMapper.createObjectNode();
        node.put("position", argument.position());
        node.put("name", argument.name());
        node.put("protocolType", argument.protocolType());
        if (!argument.composite()) {
            node.put("source", argument.source());
            putIfText(node, "sourcePath", argument.sourcePath());
            putIfText(node, "converter", argument.converter());
            if ("CONSTANT".equals(argument.source())) {
                node.set("value", parseJsonValue(argument.constantValueJson()));
            }
            return node;
        }

        ObjectNode object = node.putObject("object");
        for (FieldDescriptor field : argument.object()) {
            ObjectNode binding = object.putObject(field.targetPath());
            binding.put("source", field.source());
            putIfText(binding, "sourcePath", field.sourcePath());
            putIfText(binding, "converter", field.converter());
            if ("CONSTANT".equals(field.source())) {
                binding.set("value", parseJsonValue(field.constantValueJson()));
            }
        }
        return node;
    }

    private ObjectNode buildOutput(
            Path schemasRoot,
            EnvironmentProfile environment,
            CapabilityDescriptor capability) throws IOException {
        ObjectNode output = jsonMapper.createObjectNode();
        output.put("mode", capability.output().mode());
        if ("ENVELOPE".equals(capability.output().mode())) {
            EnvelopeProfile envelope = environment.envelopeProfiles()
                    .get(capability.output().envelopeProfile());
            if (envelope == null) {
                throw new IOException("envelope profile not found: "
                        + capability.output().envelopeProfile());
            }
            validateEnvelope(capability.id(), envelope);
            ObjectNode envelopeNode = output.putObject("envelope");
            envelopeNode.put("codePath", envelope.codePath());
            ArrayNode successValues = envelopeNode.putArray("successValues");
            envelope.successValues().forEach(successValues::add);
            envelopeNode.put("dataPath", envelope.dataPath());
            putIfText(envelopeNode, "messagePath", envelope.messagePath());
        }

        ArrayNode projection = output.putArray("projection");
        safeList(capability.output().projection()).forEach(mapping -> {
            ObjectNode item = projection.addObject();
            item.put("from", mapping.from());
            item.put("to", mapping.to());
        });
        output.set("publicSchema",
                readObjectSchema(schemasRoot, capability.output().schemaResource()));
        ArrayNode redactions = output.putArray("redactions");
        safeList(capability.output().redactions()).forEach(rule -> {
            ObjectNode item = redactions.addObject();
            item.put("path", rule.path());
            item.put("method", rule.method());
        });
        output.put("maxBytes", capability.output().maxBytes());
        return output;
    }

    private ObjectNode readObjectSchema(Path schemasRoot, String resource) throws IOException {
        requireText(resource, "schemaResource");
        Path root = schemasRoot.toRealPath();
        Path schemaPath = root.resolve(resource.replace('/', java.io.File.separatorChar))
                .normalize();
        if (!schemaPath.startsWith(root)) {
            throw new IOException("schema resource escapes schemas root: " + resource);
        }
        validateRegularFile(schemaPath, "schema resource");
        Path realSchemaPath = schemaPath.toRealPath();
        if (!realSchemaPath.startsWith(root)) {
            throw new IOException("schema resource resolves outside schemas root: " + resource);
        }
        validateFileSize(realSchemaPath, "schema resource", MAX_SCHEMA_BYTES);
        JsonNode node = jsonMapper.readTree(
                Files.readString(realSchemaPath, StandardCharsets.UTF_8));
        if (!(node instanceof ObjectNode objectNode)) {
            throw new IOException("schema root must be object: " + resource);
        }
        return objectNode.deepCopy();
    }

    private void writeReport(
            Path report,
            DescriptorDocument descriptor,
            List<String> generated,
            Map<String, List<String>> failures) throws IOException {
        ObjectNode root = jsonMapper.createObjectNode();
        root.put("descriptorVersion", descriptor.descriptorVersion());
        root.put("generatorVersion", descriptor.generatorVersion());
        root.put("success", failures.isEmpty());
        addStrings(root.putArray("generated"), generated);
        ObjectNode failureNode = root.putObject("failures");
        failures.forEach((id, errors) -> addStrings(failureNode.putArray(id), errors));
        appendFieldSources(root.putObject("fieldSources"), descriptor.capabilities());
        writeJsonAtomically(report, root);
    }

    private static void appendFieldSources(
            ObjectNode root, List<CapabilityDescriptor> capabilities) {
        safeList(capabilities).stream()
                .filter(Objects::nonNull)
                .filter(capability -> hasText(capability.id()))
                .sorted(Comparator.comparing(CapabilityDescriptor::id))
                .forEach(capability -> {
                    String descriptorSource = "descriptor:capabilities[" + capability.id() + "]";
                    ObjectNode sources = root.putObject(capability.id());
                    sources.put("metadata.id", descriptorSource + ".id");
                    sources.put("metadata.version", descriptorSource + ".version");
                    sources.put("metadata.owner",
                            "governance:policies[" + capability.policyRef() + "].owner");
                    sources.put("spec.displayName", descriptorSource + ".displayName");
                    sources.put("spec.description", descriptorSource + ".description");
                    sources.put("spec.examples",
                            "governance:policies[" + capability.policyRef() + "].examples");
                    sources.put("spec.risk", descriptorSource + ".risk");
                    sources.put("spec.inputSchema", hasText(capability.inputSchemaResource())
                            ? "schema:" + capability.inputSchemaResource()
                            : descriptorSource + ".arguments");
                    sources.put("spec.authorization",
                            "governance:policies[" + capability.policyRef() + "]");
                    sources.put("spec.invocation",
                            "profile:providers[" + capability.interfaceName() + "]");
                    String outputResource = capability.output() == null
                            ? "<missing>" : capability.output().schemaResource();
                    sources.put("spec.output", descriptorSource + ".output + schema:"
                            + outputResource);
                    sources.put("spec.resilience",
                            "profile:providers[" + capability.interfaceName() + "].resilience");
                });
    }

    private static void validateRootDocuments(
            DescriptorDocument descriptor,
            GovernanceConfig governance,
            EnvironmentProfile profile) throws IOException {
        if (descriptor == null
                || !SUPPORTED_DESCRIPTOR_VERSION.equals(descriptor.descriptorVersion())) {
            throw new IOException("unsupported descriptorVersion: "
                    + (descriptor == null ? null : descriptor.descriptorVersion()));
        }
        if (descriptor.capabilities() == null) {
            throw new IOException("descriptor capabilities must not be null");
        }
        if (descriptor.capabilities().size() > MAX_CAPABILITIES) {
            throw new IOException("descriptor capability count exceeds " + MAX_CAPABILITIES);
        }
        Set<String> ids = new HashSet<>();
        for (CapabilityDescriptor capability : descriptor.capabilities()) {
            if (capability != null && hasText(capability.id()) && !ids.add(capability.id())) {
                throw new IOException("duplicate capability id: " + capability.id());
            }
        }
        if (governance == null || governance.policies() == null) {
            throw new IOException("governance policies must not be null");
        }
        if (profile == null || profile.providers() == null
                || profile.envelopeProfiles() == null) {
            throw new IOException("profile providers/envelopeProfiles must not be null");
        }
    }

    private static void validatePolicy(String id, GovernancePolicy policy) throws IOException {
        if (policy.owner() == null) {
            throw new IOException("governance owner not found: " + id);
        }
        requireText(policy.owner().team(), id + ".owner.team");
        requireText(policy.owner().contact(), id + ".owner.contact");
        if (policy.examples() == null) {
            throw new IOException("governance examples not found: " + id);
        }
    }

    private static void validateProvider(
            CapabilityDescriptor capability, ProviderProfile provider) throws IOException {
        requireText(provider.serviceVersion(), capability.interfaceName() + ".serviceVersion");
        requireText(provider.serialization(), capability.interfaceName() + ".serialization");
        if (provider.resilience() == null) {
            throw new IOException("provider resilience not found: " + capability.interfaceName());
        }
        if (provider.resilience().timeoutMs() <= 0
                || provider.resilience().retries() < 0
                || provider.resilience().maxConcurrent() <= 0) {
            throw new IOException("provider resilience values are invalid: "
                    + capability.interfaceName());
        }
        if (capability.risk().startsWith("WRITE") && provider.resilience().retries() > 0) {
            throw new IOException("write capability retries must be 0: " + capability.id());
        }
    }

    private static void validateEnvelope(String id, EnvelopeProfile envelope) throws IOException {
        requireText(envelope.codePath(), id + ".envelope.codePath");
        requireText(envelope.dataPath(), id + ".envelope.dataPath");
        if (envelope.successValues() == null || envelope.successValues().isEmpty()) {
            throw new IOException("envelope successValues must not be empty: " + id);
        }
    }

    private static void appendAuthorization(ObjectNode spec, GovernancePolicy policy) {
        if (safeList(policy.permissions()).isEmpty()
                && safeMap(policy.principalClaims()).isEmpty()) {
            return;
        }
        ObjectNode authorization = spec.putObject("authorization");
        addStrings(authorization.putArray("permissions"), policy.permissions());
        ObjectNode claims = authorization.putObject("principalClaims");
        safeMap(policy.principalClaims()).forEach((path, claim) -> {
            ObjectNode item = claims.putObject(path);
            item.put("type", claim.type());
            item.put("required", claim.required());
        });
    }

    private static void appendExamples(ObjectNode node, Examples examples) {
        addStrings(node.putArray("positive"), examples.positive());
        addStrings(node.putArray("negative"), examples.negative());
        addStrings(node.putArray("synonyms"), examples.synonyms());
    }

    private JsonNode parseJsonValue(String json) {
        try {
            JsonNode value = jsonMapper.readTree(json);
            if (value == null || !(value.isTextual() || value.isNumber() || value.isBoolean())) {
                throw new IllegalArgumentException(
                        "constantValueJson must be a string, number, or boolean scalar");
            }
            return value;
        } catch (IOException e) {
            throw new IllegalArgumentException("constantValueJson is invalid JSON", e);
        }
    }

    private static String singlePropertyName(String pointer) throws IOException {
        if (!hasText(pointer) || !pointer.startsWith("/") || pointer.indexOf('/', 1) >= 0) {
            throw new IOException("automatic input schema requires a single-level sourcePath: "
                    + pointer);
        }
        return pointer.substring(1).replace("~1", "/").replace("~0", "~");
    }

    private static void validateRegularFile(Path path, String label) throws IOException {
        if (path == null || !Files.isRegularFile(path)) {
            throw new IOException(label + " file not found: " + path);
        }
    }

    private static void validateFileSize(Path path, String label, long maxBytes)
            throws IOException {
        long size = Files.size(path);
        if (size > maxBytes) {
            throw new IOException(label + " exceeds " + maxBytes + " bytes");
        }
    }

    private static Path resolveOutputPath(Path outputDirectory, String capabilityId)
            throws IOException {
        Path root = outputDirectory.toAbsolutePath().normalize();
        Path output = outputDirectory.resolve(capabilityId + ".json")
                .toAbsolutePath().normalize();
        if (!output.startsWith(root)) {
            throw new IOException(
                    "capability id resolves outside output directory: " + capabilityId);
        }
        return output;
    }

    private void writeJsonAtomically(Path target, JsonNode value) throws IOException {
        Path absoluteTarget = target.toAbsolutePath().normalize();
        Path parent = absoluteTarget.getParent();
        if (parent == null) {
            throw new IOException("output path has no parent: " + target);
        }
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, ".manifest-", ".tmp");
        try {
            Files.writeString(
                    temporary,
                    jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value)
                            + System.lineSeparator(),
                    StandardCharsets.UTF_8);
            try {
                Files.move(
                        temporary,
                        absoluteTarget,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, absoluteTarget, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void requireText(String value, String field) throws IOException {
        if (!hasText(value)) {
            throw new IOException(field + " must not be blank");
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static void putIfText(ObjectNode node, String field, String value) {
        if (hasText(value)) {
            node.put(field, value);
        }
    }

    private static void addStrings(ArrayNode node, List<String> values) {
        safeList(values).forEach(node::add);
    }

    private static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static <K, V> Map<K, V> safeMap(Map<K, V> values) {
        return values == null ? Map.of() : values;
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return hasText(message) ? message : exception.getClass().getSimpleName();
    }

    record GenerationRequest(
            Path descriptor,
            Path schemasRoot,
            Path governance,
            Path profile,
            Path outputDirectory,
            Path report) {
    }

    record GenerationResult(
            List<String> generated,
            Map<String, List<String>> failures) {

        boolean successful() {
            return failures.isEmpty();
        }
    }
}
