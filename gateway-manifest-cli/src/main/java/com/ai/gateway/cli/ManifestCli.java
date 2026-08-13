package com.ai.gateway.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Offline CLI entry point for generating and validating Capability Manifests
 * (design document ).
 *
 * <p>Supported commands:</p>
 * <ul>
 * <li>{@code generate-dubbo --api-jar <path> --interface <name> --method <name> --output <file>}
 * &mdash; generates a Manifest from a Dubbo API JAR.</li>
 * <li>{@code validate --manifest <file>} &mdash; validates a Manifest against the
 * Capability Manifest JSON Schema.</li>
 * <li>{@code help} &mdash; prints usage.</li>
 * </ul>
 *
 * <h2>Process isolation notes</h2>
 * <p>Manifest generation from arbitrary API JARs executes untrusted bytecode metadata and
 * MUST run in a hardened, isolated process. The sandbox wrapper around this CLI is expected
 * to enforce the following (not implemented here):</p>
 * <ul>
 * <li>close all network access (no egress / ingress);</li>
 * <li>cap CPU time and heap/native memory;</li>
 * <li>cap output file size;</li>
 * <li>mount the input directory read-only and the output directory write-only.</li>
 * </ul>
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
            case "generate-dubbo":
                return generateDubbo(options);
            case "validate":
                return validate(options);
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

    private static int generateDubbo(Map<String, String> options) {
        String apiJar = options.get("api-jar");
        String interfaceName = options.get("interface");
        String method = options.get("method");
        String output = options.get("output");

        if (isBlank(apiJar) || isBlank(interfaceName) || isBlank(method) || isBlank(output)) {
            System.err.println("generate-dubbo requires: --api-jar <path> --interface <name> "
                    + "--method <name> --output <file>");
            return 1;
        }

        // Placeholder: real implementation parses .class files from the API JAR via ASM and
        // projects a Manifest. ASM-based class file parsing not yet implemented
        //.
        System.out.println("ASM-based class file parsing not yet implemented");
        System.out.println(" api-jar = " + apiJar);
        System.out.println(" interface = " + interfaceName);
        System.out.println(" method = " + method);
        System.out.println(" output = " + output);
        return 0;
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
        System.out.println(" manifest-cli generate-dubbo --api-jar <path> --interface <name> "
                + "--method <name> --output <file>");
        System.out.println(" Generate a Capability Manifest from a Dubbo API JAR.");
        System.out.println();
        System.out.println(" manifest-cli validate --manifest <file>");
        System.out.println(" Validate a Manifest (.json/.yaml/.yml) against the Capability "
                + "Manifest JSON Schema.");
        System.out.println();
        System.out.println(" manifest-cli help");
        System.out.println(" Print this usage information.");
    }
}
