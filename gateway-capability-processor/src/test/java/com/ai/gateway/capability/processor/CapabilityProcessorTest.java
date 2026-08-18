package com.ai.gateway.capability.processor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityProcessorTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldGenerateDescriptorForValidInterface() throws Exception {
        CompilationResult result = compile("sample.OrderApi", """
                package sample;

                import com.ai.gateway.capability.annotation.*;

                @CapabilityGroup(idPrefix = "order", protocol = CapabilityProtocol.DUBBO)
                public interface OrderApi {

                    /**
                     * 查询订单。不会把后续说明写入摘要。
                     *
                     * @param orderNo 订单编号
                     */
                    @Capability(
                            id = "order.query",
                            version = "1.0.0",
                            risk = CapabilityRisk.READ_ONLY,
                            policyRef = "order-query")
                    @CapOutput(
                            mode = CapabilityOutputMode.DIRECT,
                            schemaResource = "schemas/order-output.json")
                    String query(@CapArg(source = CapabilityArgumentSource.MODEL) String orderNo);

                    String internalOnly(String raw);
                }
                """);

        assertThat(result.success())
                .withFailMessage(result::diagnosticMessages)
                .isTrue();
        JsonNode descriptor = readDescriptor(result);
        JsonNode capability = descriptor.path("capabilities").path(0);
        assertThat(descriptor.path("capabilities")).hasSize(1);
        assertThat(descriptor.path("descriptorVersion").asText()).isEqualTo("1.0");
        assertThat(capability.path("id").asText()).isEqualTo("order.query");
        assertThat(capability.path("interfaceName").asText()).isEqualTo("sample.OrderApi");
        assertThat(capability.path("description").asText()).isEqualTo("查询订单。");
        assertThat(capability.path("arguments").path(0).path("sourcePath").asText())
                .isEqualTo("/orderNo");
        assertThat(capability.path("arguments").path(0).path("description").asText())
                .isEqualTo("订单编号");
    }

    @Test
    void shouldRejectParameterWithoutBindingAnnotation() throws Exception {
        CompilationResult result = compile("sample.InvalidOrderApi", """
                package sample;

                import com.ai.gateway.capability.annotation.*;

                @CapabilityGroup(idPrefix = "order", protocol = CapabilityProtocol.DUBBO)
                public interface InvalidOrderApi {

                    @Capability(
                            id = "order.query",
                            version = "1.0.0",
                            risk = CapabilityRisk.READ_ONLY,
                            policyRef = "order-query",
                            description = "查询订单")
                    @CapOutput(
                            mode = CapabilityOutputMode.DIRECT,
                            schemaResource = "schemas/order-output.json")
                    String query(String orderNo);
                }
                """);

        assertThat(result.success()).isFalse();
        assertThat(result.diagnosticMessages())
                .contains("每个参数必须且只能声明 @CapArg 或 @CapComposite");
        assertThat(result.descriptorPath()).doesNotExist();
    }

    @Test
    void shouldGenerateParseableJsonForCompositeBinding() throws Exception {
        CompilationResult result = compile("sample.CompositeOrderApi", """
                package sample;

                import com.ai.gateway.capability.annotation.*;
                import java.util.Map;

                @CapabilityGroup(idPrefix = "order", protocol = CapabilityProtocol.DUBBO)
                public interface CompositeOrderApi {

                    @Capability(
                            id = "order.detail",
                            version = "1.0.0",
                            risk = CapabilityRisk.READ_ONLY,
                            policyRef = "order-detail",
                            description = "查询订单详情")
                    @CapInput(schemaResource = "schemas/order-input.json")
                    @CapOutput(
                            mode = CapabilityOutputMode.DIRECT,
                            schemaResource = "schemas/order-output.json")
                    Map<String, Object> detail(
                            @CapComposite({
                                @CapFieldBinding(
                                        targetPath = "/orderNo",
                                        source = CapabilityArgumentSource.MODEL,
                                        sourcePath = "/orderNo"),
                                @CapFieldBinding(
                                        targetPath = "/tenantId",
                                        source = CapabilityArgumentSource.PRINCIPAL,
                                        sourcePath = "/tenantId")
                            }) Map<String, Object> request);
                }
                """);

        assertThat(result.success())
                .withFailMessage(result::diagnosticMessages)
                .isTrue();
        JsonNode descriptor = readDescriptor(result);
        JsonNode argument = descriptor.path("capabilities").path(0)
                .path("arguments").path(0);
        assertThat(argument.path("jsonType").asText()).isEqualTo("object");
        assertThat(argument.path("object")).hasSize(2);
        assertThat(argument.path("object").path(0).path("targetPath").asText())
                .isEqualTo("/orderNo");
    }

    @Test
    void shouldNotWriteDescriptorWhenOtherCompilationErrorExists() throws Exception {
        CompilationResult result = compile(
                "sample.BrokenOrderApi",
                """
                package sample;

                import com.ai.gateway.capability.annotation.*;

                @CapabilityGroup(idPrefix = "order", protocol = CapabilityProtocol.DUBBO)
                public interface BrokenOrderApi {

                    @Capability(
                            id = "order.query",
                            version = "1.0.0",
                            risk = CapabilityRisk.READ_ONLY,
                            policyRef = "order-query",
                            description = "查询订单")
                    @CapOutput(
                            mode = CapabilityOutputMode.DIRECT,
                            schemaResource = "schemas/order-output.json")
                    String query(@CapArg(source = CapabilityArgumentSource.MODEL) String orderNo);
                }
                """,
                List.of(new CapabilityProcessor(), new CompilationErrorProcessor()));

        assertThat(result.success()).isFalse();
        assertThat(result.diagnosticMessages()).contains("模拟其他编译处理器错误");
        assertThat(result.descriptorPath()).doesNotExist();
    }

    @Test
    void shouldRequireExplicitSchemaForComplexModelInput() throws Exception {
        CompilationResult result = compile("sample.ComplexInputApi", """
                package sample;

                import com.ai.gateway.capability.annotation.*;
                import java.util.Map;

                @CapabilityGroup(idPrefix = "order", protocol = CapabilityProtocol.DUBBO)
                public interface ComplexInputApi {

                    @Capability(
                            id = "order.complex",
                            version = "1.0.0",
                            risk = CapabilityRisk.READ_ONLY,
                            policyRef = "order-complex",
                            description = "复杂输入查询")
                    @CapOutput(
                            mode = CapabilityOutputMode.DIRECT,
                            schemaResource = "schemas/order-output.json")
                    String query(@CapArg(source = CapabilityArgumentSource.MODEL)
                            Map<String, Object> request);
                }
                """);

        assertThat(result.success()).isFalse();
        assertThat(result.diagnosticMessages())
                .contains("复合或非标量 MODEL 输入必须声明 @CapInput.schemaResource");
        assertThat(result.descriptorPath()).doesNotExist();
    }

    @Test
    void shouldRejectAnnotatedMethodOverloads() throws Exception {
        CompilationResult result = compile("sample.OverloadedOrderApi", """
                package sample;

                import com.ai.gateway.capability.annotation.*;

                @CapabilityGroup(idPrefix = "order", protocol = CapabilityProtocol.DUBBO)
                public interface OverloadedOrderApi {

                    @Capability(
                            id = "order.by-number",
                            version = "1.0.0",
                            risk = CapabilityRisk.READ_ONLY,
                            policyRef = "order-read",
                            description = "按编号查询")
                    @CapOutput(mode = CapabilityOutputMode.DIRECT,
                            schemaResource = "schemas/order-output.json")
                    String query(@CapArg(source = CapabilityArgumentSource.MODEL) String orderNo);

                    @Capability(
                            id = "order.by-id",
                            version = "1.0.0",
                            risk = CapabilityRisk.READ_ONLY,
                            policyRef = "order-read",
                            description = "按主键查询")
                    @CapOutput(mode = CapabilityOutputMode.DIRECT,
                            schemaResource = "schemas/order-output.json")
                    String query(@CapArg(source = CapabilityArgumentSource.MODEL) Long orderId);
                }
                """);

        assertThat(result.success()).isFalse();
        assertThat(result.diagnosticMessages()).contains("能力方法不支持重载");
        assertThat(result.descriptorPath()).doesNotExist();
    }

    @Test
    void shouldRejectNonScalarConstantValue() throws Exception {
        CompilationResult result = compile("sample.ConstantOrderApi", """
                package sample;

                import com.ai.gateway.capability.annotation.*;

                @CapabilityGroup(idPrefix = "order", protocol = CapabilityProtocol.DUBBO)
                public interface ConstantOrderApi {

                    @Capability(
                            id = "order.constant",
                            version = "1.0.0",
                            risk = CapabilityRisk.READ_ONLY,
                            policyRef = "order-read",
                            description = "固定条件查询")
                    @CapOutput(mode = CapabilityOutputMode.DIRECT,
                            schemaResource = "schemas/order-output.json")
                    String query(@CapArg(
                            source = CapabilityArgumentSource.CONSTANT,
                            constantValueJson = "{\"enabled\":true}") String filter);
                }
                """);

        assertThat(result.success()).isFalse();
        assertThat(result.diagnosticMessages())
                .contains("constantValueJson 必须是字符串、数字或布尔 JSON 标量");
        assertThat(result.descriptorPath()).doesNotExist();
    }

    @Test
    void shouldRejectBlankOutputSchemaResource() throws Exception {
        CompilationResult result = compile("sample.MissingOutputApi", """
                package sample;

                import com.ai.gateway.capability.annotation.*;

                @CapabilityGroup(idPrefix = "order", protocol = CapabilityProtocol.DUBBO)
                public interface MissingOutputApi {

                    @Capability(
                            id = "order.missing-output",
                            version = "1.0.0",
                            risk = CapabilityRisk.READ_ONLY,
                            policyRef = "order-read",
                            description = "缺少输出契约")
                    @CapOutput(mode = CapabilityOutputMode.DIRECT, schemaResource = " ")
                    String query(@CapArg(source = CapabilityArgumentSource.MODEL) String orderNo);
                }
                """);

        assertThat(result.success()).isFalse();
        assertThat(result.diagnosticMessages()).contains("@CapOutput.schemaResource 不能为空");
        assertThat(result.descriptorPath()).doesNotExist();
    }

    private CompilationResult compile(String className, String source) throws IOException {
        return compile(className, source, List.of(new CapabilityProcessor()));
    }

    private CompilationResult compile(
            String className,
            String source,
            List<? extends Processor> processors) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler)
                .as("Processor 测试必须运行在完整 JDK 中")
                .isNotNull();

        Path outputDirectory = Files.createDirectories(
                temporaryDirectory.resolve(className.replace('.', '-')));
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(
                diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            fileManager.setLocationFromPaths(
                    StandardLocation.CLASS_OUTPUT, List.of(outputDirectory));
            JavaFileObject sourceFile = new StringSource(className, source);
            JavaCompiler.CompilationTask task = compiler.getTask(
                    null,
                    fileManager,
                    diagnostics,
                    List.of(
                            "--release", "17",
                            "-classpath", System.getProperty("java.class.path"),
                            "-parameters"),
                    null,
                    List.of(sourceFile));
            task.setProcessors(processors);
            boolean success = Boolean.TRUE.equals(task.call());
            return new CompilationResult(success, outputDirectory, diagnostics.getDiagnostics());
        }
    }

    private static JsonNode readDescriptor(CompilationResult result) throws IOException {
        assertThat(result.descriptorPath()).isRegularFile();
        return OBJECT_MAPPER.readTree(result.descriptorPath().toFile());
    }

    private record CompilationResult(
            boolean success,
            Path outputDirectory,
            List<Diagnostic<? extends JavaFileObject>> diagnostics) {

        Path descriptorPath() {
            return outputDirectory.resolve(CapabilityProcessor.DESCRIPTOR_PATH);
        }

        String diagnosticMessages() {
            return diagnostics.stream()
                    .map(diagnostic -> diagnostic.getKind() + ": "
                            + diagnostic.getMessage(Locale.ROOT))
                    .reduce((left, right) -> left + System.lineSeparator() + right)
                    .orElse("无编译诊断");
        }
    }

    private static final class StringSource extends SimpleJavaFileObject {

        private final String source;

        private StringSource(String className, String source) {
            super(URI.create("string:///" + className.replace('.', '/') + Kind.SOURCE.extension),
                    Kind.SOURCE);
            this.source = source;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return source;
        }
    }

    @SupportedAnnotationTypes("*")
    @SupportedSourceVersion(SourceVersion.RELEASE_17)
    private static final class CompilationErrorProcessor extends AbstractProcessor {

        private boolean errorReported;

        @Override
        public boolean process(
                Set<? extends TypeElement> annotations, RoundEnvironment roundEnvironment) {
            if (!errorReported && !roundEnvironment.processingOver()) {
                processingEnv.getMessager().printMessage(
                        Diagnostic.Kind.ERROR, "模拟其他编译处理器错误");
                errorReported = true;
            }
            return false;
        }
    }
}
