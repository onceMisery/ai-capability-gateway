package com.ai.gateway.example.demo;

import com.ai.gateway.example.client.GatewayApiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Demonstrates the natural language query workflow under both
 * authentication modes supported by the gateway.
 *
 * <p>This example shows:
 * <ol>
 * <li>Simple query that returns COMPLETED</li>
 * <li>Query requiring clarification (missing parameters)</li>
 * <li>Clarification continuation</li>
 * <li>Query with no matching capability</li>
 * <li>Error handling (authentication failure, timeout)</li>
 * </ol>
 *
 * <h3>Authentication modes</h3>
 *
 * <p>The demo picks the authentication mode in this priority order:</p>
 * <ol>
 * <li>{@code args[2]} — explicit {@code stub} | {@code sa-token} | {@code custom}.</li>
 * <li>{@code GATEWAY_AUTH_MODE} environment variable — same values.</li>
 * <li>Default: {@code stub} (works with the default {@code gateway.auth.provider=stub}).</li>
 * </ol>
 *
 * <table>
 * <tr><th>Mode</th><th>Source of token</th></tr>
 * <tr><td>{@code stub}</td><td>Placeholder string {@code demo-jwt-token} (accepted by the stub AuthenticationPort).</td></tr>
 * <tr><td>{@code sa-token}</td><td>Minted by {@link SaTokenIssuer} using {@code GATEWAY_AUTH_JWT_SECRET} and {@code GATEWAY_AUTH_LOGIN_ID}.</td></tr>
 * <tr><td>{@code custom}</td><td>Caller-supplied token via {@code args[1]} or {@code GATEWAY_AUTH_TOKEN}.</td></tr>
 * </table>
 *
 * <p>Prerequisites:
 * <ul>
 * <li>Gateway running at http://localhost:8080</li>
 * <li>A token valid for the configured {@code gateway.auth.provider}</li>
 * <li>At least one PUBLISHED capability in the catalog</li>
 * </ul>
 *
 * <p>Run with:
 * <pre>{@code
 * java -cp gateway-example.jar com.ai.gateway.example.demo.NaturalLanguageQueryDemo
 * }</pre>
 *
 * @since 0.1.0
 */
public class NaturalLanguageQueryDemo {

    private static final Logger log = LoggerFactory.getLogger(NaturalLanguageQueryDemo.class);

    private static final String DEFAULT_BASE_URL = "http://localhost:8080";
    private static final String DEFAULT_TOKEN = "demo-jwt-token";

    private static final String MODE_STUB = "stub";
    private static final String MODE_SA_TOKEN = "sa-token";
    private static final String MODE_CUSTOM = "custom";

    /**
     * Main entry point for the natural-language query demo.
     *
     * <p>Accepts optional command-line arguments:</p>
     * <ul>
     * <li>args[0] — gateway base URL (default: http://localhost:8080)</li>
     * <li>args[1] — pre-minted bearer token (used in {@code custom} mode)</li>
     * <li>args[2] — authentication mode override: {@code stub} | {@code sa-token} | {@code custom}</li>
     * </ul>
     *
     * <p>Recognized environment variables:</p>
     * <ul>
     * <li>{@code GATEWAY_AUTH_MODE} — fallback auth mode</li>
     * <li>{@code GATEWAY_AUTH_JWT_SECRET} — shared HMAC secret (used in {@code sa-token} mode)</li>
     * <li>{@code GATEWAY_AUTH_LOGIN_ID} — Sa-Token login subject (default: {@code demo-user})</li>
     * <li>{@code GATEWAY_AUTH_LOGIN_TYPE} — Sa-Token login type (default: {@code login})</li>
     * <li>{@code GATEWAY_AUTH_TIMEOUT_SECONDS} — token lifetime (default: 7200)</li>
     * <li>{@code GATEWAY_AUTH_TOKEN} — fallback pre-minted token for {@code custom} mode</li>
     * </ul>
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        String baseUrl = args.length > 0 ? args[0] : DEFAULT_BASE_URL;
        String cliToken = args.length > 1 ? args[1] : null;
        String cliMode = args.length > 2 ? args[2] : null;

        String mode = resolveAuthMode(cliMode);

        // Resolve token based on mode
        String token;
        String modeDisplay;
        switch (mode) {
            case MODE_SA_TOKEN:
                String secret = System.getenv("GATEWAY_AUTH_JWT_SECRET");
                if (secret == null || secret.isBlank()) {
                    System.err.println("[FATAL] GATEWAY_AUTH_JWT_SECRET is required in sa-token mode. "
                            + "Set it to the same value as gateway.auth.sa-token.jwt-secret-key on the gateway.");
                    return;
                }
                String loginType = firstNonBlank(System.getenv("GATEWAY_AUTH_LOGIN_TYPE"),
                        SaTokenIssuer.DEFAULT_LOGIN_TYPE);
                String loginId = firstNonBlank(System.getenv("GATEWAY_AUTH_LOGIN_ID"), "demo-user");
                long timeout = parseLong(System.getenv("GATEWAY_AUTH_TIMEOUT_SECONDS"),
                        SaTokenIssuer.DEFAULT_ACCESS_TOKEN_TIMEOUT_SECONDS);
                SaTokenIssuer issuer = new SaTokenIssuer(secret, loginType);
                token = issuer.issue(loginId, 10001L,
                        List.of("user"), List.of("*"), timeout);
                modeDisplay = MODE_SA_TOKEN + " (loginId=" + loginId
                        + ", loginType=" + loginType + ", ttl=" + timeout + "s)";
                break;
            case MODE_CUSTOM:
                String envToken = System.getenv("GATEWAY_AUTH_TOKEN");
                token = cliToken != null ? cliToken : envToken;
                if (token == null || token.isBlank()) {
                    System.err.println("[FATAL] custom mode requires a token via args[1] or GATEWAY_AUTH_TOKEN.");
                    return;
                }
                modeDisplay = MODE_CUSTOM + " (length=" + token.length() + ")";
                break;
            case MODE_STUB:
            default:
                token = cliToken != null ? cliToken : DEFAULT_TOKEN;
                modeDisplay = MODE_STUB + " (gateway.auth.provider=stub)";
                break;
        }

        System.out.println("=".repeat(70));
        System.out.println("AI Capability Gateway — Natural Language Query Demo");
        System.out.println("=".repeat(70));
        System.out.println("Gateway URL : " + baseUrl);
        System.out.println("Auth mode   : " + modeDisplay);
        System.out.println();

        GatewayApiClient client = new GatewayApiClient(baseUrl, token);

        // Example 1: Direct query that should return COMPLETED
        exampleDirectQuery(client);

        // Example 2: Clarification flow (missing parameters)
        exampleClarificationFlow(client);

        // Example 3: No matching capability
        exampleNoMatch(client);

        // Example 4: Error handling
        exampleErrorHandling(baseUrl);

        System.out.println();
        System.out.println("=".repeat(70));
        System.out.println("Demo complete.");
        System.out.println("=".repeat(70));
    }

    /**
     * Resolves the authentication mode from CLI → env → default.
     */
    private static String resolveAuthMode(String cliMode) {
        String mode = cliMode;
        if (mode == null || mode.isBlank()) {
            mode = System.getenv("GATEWAY_AUTH_MODE");
        }
        if (mode == null || mode.isBlank()) {
            return MODE_STUB;
        }
        String normalized = mode.trim().toLowerCase();
        if (!MODE_STUB.equals(normalized)
                && !MODE_SA_TOKEN.equals(normalized)
                && !MODE_CUSTOM.equals(normalized)) {
            System.err.println("[WARN] Unknown auth mode '" + mode + "', falling back to stub.");
            return MODE_STUB;
        }
        return normalized;
    }

    private static String firstNonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a : b;
    }

    private static long parseLong(String value, long fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * Example 1: Direct query that returns COMPLETED.
     *
     * <p>Demonstrates a well-formed query where all required parameters
     * can be extracted by the model from the natural-language text.</p>
     *
     * <p>Expected flow:</p>
     * <ol>
     * <li>The query "查询订单 SO202607210001" matches the
     * {@code order.detail.query} capability via BM25 retrieval.</li>
     * <li>The model extracts {@code orderNo = "SO202607210001"}.</li>
     * <li>Input Schema validation passes (pattern: ^SO[0-9]{12}$).</li>
     * <li>Parameter binding injects orgId from Principal.</li>
     * <li>The Dubbo generic invocation calls OrderQueryApi#query.</li>
     * <li>The response is unwrapped (envelope), projected, and redacted.</li>
     * </ol>
     *
     * @param client the gateway API client
     */
    private static void exampleDirectQuery(GatewayApiClient client) {
        printSection("Example 1: Direct Query (COMPLETED)");

        try {
            // This query contains all required parameters (orderNo)
            Map<String, Object> result = client.naturalLanguageQuery(
                    "查询订单 SO202607210001", "zh-CN");

            String status = (String) result.get("status");
            System.out.println(" Status: " + status);

            if ("COMPLETED".equals(status)) {
                System.out.println(" Data: " + result.get("data"));
                System.out.println(" Summary: " + result.get("summary"));
                System.out.println(" Snapshot Version: " + result.get("snapshotVersion"));
            } else {
                System.out.println(" Unexpected status: " + status);
                System.out.println(" Full response: " + result);
            }
        } catch (GatewayApiClient.GatewayApiException e) {
            System.out.println(" [ERROR] " + e.getMessage());
            log.warn("Direct query failed", e);
        }

        System.out.println();
    }

    /**
     * Example 2: Clarification flow (missing parameters).
     *
     * <p>Demonstrates what happens when the model cannot extract all
     * required parameters from the initial query.</p>
     *
     * <p>Expected flow:</p>
     * <ol>
     * <li>The query "查询订单" matches {@code order.detail.query} but
     * the model cannot extract the required {@code orderNo}.</li>
     * <li>The gateway returns CLARIFICATION_REQUIRED with an
     * {@code interactionId} and a clarification question.</li>
     * <li>The user provides the missing orderNo via
     * {@code continueClarification}.</li>
     * <li>The gateway completes the query with the supplemented parameter.</li>
     * </ol>
     *
     * <p>Important constraints:</p>
     * <ul>
     * <li>The clarification session is short-lived (expiresAt).</li>
     * <li>Subsequent answers may only supplement missing information.</li>
     * <li>Intent jumps invalidate the session and require a full restart.</li>
     * </ul>
     *
     * @param client the gateway API client
     */
    private static void exampleClarificationFlow(GatewayApiClient client) {
        printSection("Example 2: Clarification Flow");

        try {
            // Step 1: Send an ambiguous query missing the required orderNo
            System.out.println(" Step 1: Sending ambiguous query...");
            Map<String, Object> initialResult = client.naturalLanguageQuery(
                    "帮我查询一下订单", "zh-CN");

            String status = (String) initialResult.get("status");
            System.out.println(" Status: " + status);

            if ("CLARIFICATION_REQUIRED".equals(status)) {
                String interactionId = (String) initialResult.get("interactionId");
                String question = (String) initialResult.get("question");

                System.out.println(" Question: " + question);
                System.out.println(" Interaction ID: " + interactionId);

                // Step 2: Continue the clarification with the missing parameter
                System.out.println();
                System.out.println(" Step 2: Providing missing parameter...");
                Map<String, Object> clarResult = client.continueClarification(
                        interactionId, "订单号是 SO202607210001");

                String clarStatus = (String) clarResult.get("status");
                System.out.println(" Status: " + clarStatus);

                if ("COMPLETED".equals(clarStatus)) {
                    System.out.println(" Data: " + clarResult.get("data"));
                } else {
                    System.out.println(" Full response: " + clarResult);
                }
            } else if ("COMPLETED".equals(status)) {
                // The model might have been able to handle it directly
                System.out.println(" (Model resolved without clarification)");
                System.out.println(" Data: " + initialResult.get("data"));
            } else {
                System.out.println(" Full response: " + initialResult);
            }
        } catch (GatewayApiClient.GatewayApiException e) {
            System.out.println(" [ERROR] " + e.getMessage());
            log.warn("Clarification flow failed", e);
        }

        System.out.println();
    }

    /**
     * Example 3: Query with no matching capability.
     *
     * <p>Demonstrates the NO_MATCH response when no capability in the
     * catalog matches the user's query.</p>
     *
     * <p>The BM25 retrieval returns no candidates above
     * the relevance threshold, or the model routing determines that
     * none of the candidates are appropriate.</p>
     *
     * @param client the gateway API client
     */
    private static void exampleNoMatch(GatewayApiClient client) {
        printSection("Example 3: No Match");

        try {
            // This query should not match any capability in the catalog
            Map<String, Object> result = client.naturalLanguageQuery(
                    "今天天气怎么样", "zh-CN");

            String status = (String) result.get("status");
            System.out.println(" Status: " + status);

            if ("NO_MATCH".equals(status)) {
                System.out.println(" Message: " + result.get("message"));
                System.out.println(" (Expected: no capability handles weather queries)");
            } else {
                System.out.println(" Full response: " + result);
            }
        } catch (GatewayApiClient.GatewayApiException e) {
            System.out.println(" [ERROR] " + e.getMessage());
            log.warn("No-match query failed", e);
        }

        System.out.println();
    }

    /**
     * Example 4: Error handling.
     *
     * <p>Demonstrates how the client handles various error conditions
     *:</p>
     * <ul>
     * <li>Authentication failure (invalid token)</li>
     * <li>Connection failure (gateway not running)</li>
     * <li>Malformed response</li>
     * </ul>
     *
     * <p>The gateway returns stable error codes that
     * clients can use for programmatic error handling:</p>
     * <ul>
     * <li>{@code AUTHENTICATION_FAILED} — invalid or expired token</li>
     * <li>{@code PERMISSION_DENIED} — insufficient permissions</li>
     * <li>{@code PROVIDER_TIMEOUT} — downstream provider timeout</li>
     * <li>{@code PROTOCOL_ERROR} — internal protocol error</li>
     * </ul>
     *
     * @param baseUrl the gateway base URL to test against
     */
    private static void exampleErrorHandling(String baseUrl) {
        printSection("Example 4: Error Handling");

        // Test with an invalid token to demonstrate authentication error.
        // Under stub mode, stub AuthenticationPort accepts any non-blank token,
        // so we use an empty string to force AUTHENTICATION_FAILED.
        // Under sa-token mode, any non-signed token produces AUTHENTICATION_FAILED.
        System.out.println(" Testing with invalid token...");
        GatewayApiClient badClient = new GatewayApiClient(
                baseUrl, "");

        try {
            Map<String, Object> result = badClient.naturalLanguageQuery(
                    "查询订单 SO202607210001", "zh-CN");

            String status = (String) result.get("status");
            System.out.println(" Status: " + status);
            System.out.println(" Error Code: " + result.get("errorCode"));
            System.out.println(" Message: " + result.get("message"));
        } catch (GatewayApiClient.GatewayApiException e) {
            System.out.println(" [EXPECTED ERROR] " + e.getMessage());
        }

        // Test connection failure with an unreachable URL
        System.out.println();
        System.out.println(" Testing with unreachable gateway...");
        GatewayApiClient unreachableClient = new GatewayApiClient(
                "http://localhost:19999", "some-token");

        try {
            unreachableClient.getHealth();
            System.out.println(" (Unexpectedly succeeded)");
        } catch (GatewayApiClient.GatewayApiException e) {
            System.out.println(" [EXPECTED ERROR] Connection failed: "
                    + e.getMessage());
        }

        System.out.println();
    }

    /**
     * Prints a section header for the demo output.
     *
     * @param title the section title
     */
    private static void printSection(String title) {
        System.out.println("-".repeat(70));
        System.out.println(title);
        System.out.println("-".repeat(70));
    }
}
