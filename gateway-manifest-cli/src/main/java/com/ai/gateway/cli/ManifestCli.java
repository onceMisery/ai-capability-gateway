package com.ai.gateway.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Capability Manifest 离线生成与校验入口。
 *
 * <p>{@code generate} 只读取 APT 描述符、治理配置、环境 Profile 和 Schema
 * 资源，不加载或执行 Provider 业务 JAR；生成结果始终是待人工确认的 Draft。</p>
 */
public final class ManifestCli {

    private ManifestCli() {
    }

    public static void main(String[] args) {
        int exitCode = run(args);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    /**
     * Executes the CLI and returns a process exit code (0 = success).
     *
     * @param args command line arguments
     * @return exit code
     */
    static int run(String[] args) {
        if (args.length == 0) {
            printUsage();
            return 1;
        }

        String command = args[0];
        Map<String, String> options = parseOptions(args);

        switch (command) {
            case "validate":
                return validate(options);
            case "generate":
                return generate(options);
            case "help":
            case "--help":
            case "-h":
                printUsage();
                return 0;
            default:
                System.err.println("Unknown command: " + command);
                printUsage();
                return 1;
        }
    }

    private static int validate(Map<String, String> options) {
        String manifestPath = options.get("manifest");
        if (isBlank(manifestPath)) {
            System.err.println("validate requires: --manifest <file>");
            return 1;
        }

        Path path = Path.of(manifestPath);
        if (!Files.isRegularFile(path)) {
            System.err.println("Manifest file not found: " + manifestPath);
            return 1;
        }

        String content;
        try {
            content = Files.readString(path);
        } catch (IOException e) {
            System.err.println("Failed to read manifest file: " + e.getMessage());
            return 1;
        }

        ManifestSchemaValidator validator;
        try {
            validator = new ManifestSchemaValidator();
        } catch (RuntimeException e) {
            System.err.println("Failed to load schema: " + e.getMessage());
            return 1;
        }

        List<String> errors;
        try {
            if (isYaml(manifestPath)) {
                errors = validator.validateYaml(content);
            } else {
                errors = validator.validate(content);
            }
        } catch (IOException e) {
            System.err.println("Failed to parse manifest: " + e.getMessage());
            return 1;
        }

        if (errors.isEmpty()) {
            System.out.println("Manifest is valid: " + manifestPath);
            return 0;
        }

        System.err.println("Manifest validation failed with " + errors.size() + " error(s):");
        for (String error : errors) {
            System.err.println(" - " + error);
        }
        return 1;
    }

    private static int generate(Map<String, String> options) {
        List<String> required = List.of(
                "descriptor", "schemas", "governance", "profile", "out", "report");
        List<String> missing = required.stream()
                .filter(name -> isBlank(options.get(name)))
                .toList();
        if (!missing.isEmpty()) {
            System.err.println("generate requires options: " + String.join(", ", missing));
            return 1;
        }

        ManifestDraftGenerator.GenerationRequest request =
                new ManifestDraftGenerator.GenerationRequest(
                        Path.of(options.get("descriptor")),
                        Path.of(options.get("schemas")),
                        Path.of(options.get("governance")),
                        Path.of(options.get("profile")),
                        Path.of(options.get("out")),
                        Path.of(options.get("report")));
        try {
            ManifestDraftGenerator.GenerationResult result =
                    new ManifestDraftGenerator().generate(request);
            System.out.println("Generated " + result.generated().size()
                    + " Manifest Draft(s). Report: " + request.report());
            if (result.successful()) {
                return 0;
            }
            result.failures().forEach((id, errors) -> {
                System.err.println("Generation failed for " + id + ":");
                errors.forEach(error -> System.err.println(" - " + error));
            });
            return 1;
        } catch (IOException | RuntimeException e) {
            System.err.println("Manifest generation failed: " + e.getMessage());
            return 1;
        }
    }

    private static boolean isYaml(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".yaml") || lower.endsWith(".yml");
    }

    private static Map<String, String> parseOptions(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (int i = 1; i < args.length; i++) {
            String token = args[i];
            if (token.startsWith("--")) {
                String key = token.substring(2);
                String value = (i + 1 < args.length && !args[i + 1].startsWith("--"))
                        ? args[++i]
                        : "";
                options.put(key, value);
            }
        }
        return options;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static void printUsage() {
        System.out.println("Gateway Manifest CLI");
        System.out.println();
        System.out.println("Usage:");
        System.out.println(" manifest-cli validate --manifest <file>");
        System.out.println(" Validate a Manifest (.json/.yaml/.yml) against the Capability "
                + "Manifest JSON Schema.");
        System.out.println();
        System.out.println(" manifest-cli generate --descriptor <file> --schemas <dir>");
        System.out.println("   --governance <file> --profile <file> --out <dir> --report <file>");
        System.out.println(" Generate reviewed Manifest Drafts from a capability descriptor.");
        System.out.println();
        System.out.println(" manifest-cli help");
        System.out.println(" Print this usage information.");
    }
}
