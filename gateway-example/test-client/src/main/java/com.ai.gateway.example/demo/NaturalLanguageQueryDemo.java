package com.ai.gateway.example.demo;

import com.ai.gateway.example.client.GatewayApiClient;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
 * 演示在网关支持的两种鉴权模式下进行自然语言查询的工作流。
 *
 * <p>本示例展示：
 * <ol>
 * <li>返回 COMPLETED 的简单查询</li>
 * <li>需要澄清的查询（参数缺失）</li>
 * <li>澄清续接</li>
 * <li>无匹配能力的查询</li>
 * <li>错误处理（鉴权失败、超时）</li>
 * </ol>
 *
 * <h3>鉴权模式</h3>
 *
 * <p>示例按如下优先级选择鉴权模式：</p>
 * <ol>
 * <li>{@code args[2]} — 显式指定 {@code stub} | {@code sa-token} | {@code custom}。</li>
 * <li>{@code GATEWAY_AUTH_MODE} 环境变量 — 取值同上。</li>
 * <li>默认：{@code stub}（配合默认的 {@code gateway.auth.provider=stub} 生效）。</li>
 * </ol>
 *
 * <table>
 * <tr><th>模式</th><th>Token 来源</th></tr>
 * <tr><td>{@code stub}</td><td>占位串 {@code demo-jwt-token}（被 stub AuthenticationPort 接受）。</td></tr>
 * <tr><td>{@code sa-token}</td><td>由 {@link SaTokenIssuer} 使用 {@code GATEWAY_AUTH_JWT_SECRET} 与
 * {@code GATEWAY_AUTH_LOGIN_ID} 签发。</td></tr>
 * <tr><td>{@code custom}</td><td>调用方通过 {@code args[1]} 或 {@code GATEWAY_AUTH_TOKEN} 提供的 Token。</td></tr>
 * </table>
 *
 * <p>前置条件：
 * <ul>
 * <li>网关运行于 http://localhost:8080</li>
 * <li>对配置的 {@code gateway.auth.provider} 有效的 Token</li>
 * <li>目录中至少存在一个 PUBLISHED 能力</li>
 * </ul>
 *
 * <p>运行方式：
 * <pre>{@code
 * java -cp gateway-example.jar com.ai.gateway.example.demo.NaturalLanguageQueryDemo
 * }</pre>
 *
 * @author cmiracle@163.com
 * @since 0.1.0
 */
@Slf4j
public class NaturalLanguageQueryDemo {

    private static final String DEFAULT_BASE_URL = "http://localhost:8080";
    private static final String DEFAULT_TOKEN = "demo-jwt-token";

    private static final String MODE_STUB = "stub";
    private static final String MODE_SA_TOKEN = "sa-token";
    private static final String MODE_CUSTOM = "custom";

    /**
     * 自然语言查询示例的入口方法。
     *
     * <p>接受可选的命令行参数：</p>
     * <ul>
     * <li>args[0] — 网关基础 URL（默认：http://localhost:8080）</li>
     * <li>args[1] — 预签发的 Bearer Token（用于 {@code custom} 模式）</li>
     * <li>args[2] — 鉴权模式覆盖：{@code stub} | {@code sa-token} | {@code custom}</li>
     * </ul>
     *
     * <p>识别的环境变量：</p>
     * <ul>
     * <li>{@code GATEWAY_AUTH_MODE} — 兜底鉴权模式</li>
     * <li>{@code GATEWAY_AUTH_JWT_SECRET} — 共享 HMAC 密钥（{@code sa-token} 模式使用）</li>
     * <li>{@code GATEWAY_AUTH_LOGIN_ID} — Sa-Token 登录主体（默认：{@code demo-user}）</li>
     * <li>{@code GATEWAY_AUTH_LOGIN_TYPE} — Sa-Token 登录类型（默认：{@code login}）</li>
     * <li>{@code GATEWAY_AUTH_TIMEOUT_SECONDS} — Token 有效期（默认：7200）</li>
     * <li>{@code GATEWAY_AUTH_TOKEN} — {@code custom} 模式的兜底预签发 Token</li>
     * </ul>
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        String baseUrl = args.length > 0 ? args[0] : DEFAULT_BASE_URL;
        String cliToken = args.length > 1 ? args[1] : null;
        String cliMode = args.length > 2 ? args[2] : null;

        String mode = resolveAuthMode(cliMode);

        // 依据模式解析 Token
        String token;
        String modeDisplay;
        switch (mode) {
            case MODE_SA_TOKEN:
                String secret = System.getenv("GATEWAY_AUTH_JWT_SECRET");
                if (secret == null || secret.isBlank()) {
                    System.err.println("[FATAL] sa-token 模式需要 GATEWAY_AUTH_JWT_SECRET。"
                            + "请将其设为与网关侧 gateway.auth.sa-token.jwt-secret-key 相同的值。");
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
                    System.err.println("[FATAL] custom 模式需要通过 args[1] 或 GATEWAY_AUTH_TOKEN 提供 Token。");
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

        // 示例 1：直接查询，预期返回 COMPLETED
        exampleDirectQuery(client);

        // 示例 2：澄清流程（参数缺失）
        exampleClarificationFlow(client);

        // 示例 3：无匹配能力
        exampleNoMatch(client);

        // 示例 4：错误处理
        exampleErrorHandling(baseUrl);

        System.out.println();
        System.out.println("=".repeat(70));
        System.out.println("Demo complete.");
        System.out.println("=".repeat(70));
    }

    /**
     * 按 CLI → 环境变量 → 默认值的顺序解析鉴权模式。
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
            System.err.println("[WARN] 未知鉴权模式 '" + mode + "'，回退到 stub。");
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
     * 示例 1：返回 COMPLETED 的直接查询。
     *
     * <p>演示一条结构良好、所有必填参数都能由模型从自然语言文本中提取的查询。</p>
     *
     * <p>预期流程：</p>
     * <ol>
     * <li>查询 "查询订单 SO202607210001" 通过 BM25 检索匹配到
     * {@code order.detail.query} 能力。</li>
     * <li>模型提取 {@code orderNo = "SO202607210001"}。</li>
     * <li>入参 Schema 校验通过（pattern: ^SO[0-9]{12}$）。</li>
     * <li>参数绑定从 Principal 注入 orgId。</li>
     * <li>Dubbo 泛化调用 OrderQueryApi#query。</li>
     * <li>响应被解包（envelope）、投影并脱敏。</li>
     * </ol>
     *
     * @param client 网关 API 客户端
     */
    private static void exampleDirectQuery(GatewayApiClient client) {
        printSection("Example 1: Direct Query (COMPLETED)");

        try {
            // 该查询包含全部必填参数（orderNo）
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
     * 示例 2：澄清流程（参数缺失）。
     *
     * <p>演示当模型无法从初始查询中提取全部必填参数时发生的情况。</p>
     *
     * <p>预期流程：</p>
     * <ol>
     * <li>查询 "查询订单" 匹配到 {@code order.detail.query}，但模型无法提取必填的
     * {@code orderNo}。</li>
     * <li>网关返回 CLARIFICATION_REQUIRED，附带 {@code interactionId} 与澄清问题。</li>
     * <li>用户通过 {@code continueClarification} 提供缺失的 orderNo。</li>
     * <li>网关凭补充参数完成查询。</li>
     * </ol>
     *
     * <p>重要约束：</p>
     * <ul>
     * <li>澄清会话短时效（expiresAt）。</li>
     * <li>后续回答只能补充缺失信息。</li>
     * <li>意图跳转会使会话失效并需完整重启。</li>
     * </ul>
     *
     * @param client 网关 API 客户端
     */
    private static void exampleClarificationFlow(GatewayApiClient client) {
        printSection("Example 2: Clarification Flow");

        try {
            // 步骤 1：发送缺少必填 orderNo 的歧义查询
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

                // 步骤 2：使用缺失参数续接澄清
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
                // 模型可能已能直接处理
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
     * 示例 3：无匹配能力的查询。
     *
     * <p>演示当目录中没有任何能力匹配用户查询时返回的 NO_MATCH 响应。</p>
     *
     * <p>BM25 检索未返回高于相关度阈值的候选，或模型路由判定没有任何候选合适。</p>
     *
     * @param client 网关 API 客户端
     */
    private static void exampleNoMatch(GatewayApiClient client) {
        printSection("Example 3: No Match");

        try {
            // 该查询不应匹配目录中的任何能力
            Map<String, Object> result = client.naturalLanguageQuery(
                    "今天天气怎么样", "zh-CN");

            String status = (String) result.get("status");
            System.out.println(" Status: " + status);

            if ("NO_MATCH".equals(status)) {
                System.out.println(" Message: " + result.get("message"));
                System.out.println(" (预期：没有能力处理天气查询)");
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
     * 示例 4：错误处理。
     *
     * <p>演示客户端如何处理各类错误条件：</p>
     * <ul>
     * <li>鉴权失败（Token 无效）</li>
     * <li>连接失败（网关未运行）</li>
     * <li>响应格式异常</li>
     * </ul>
     *
     * <p>网关返回稳定的错误码，客户端可据此进行程序化处理：</p>
     * <ul>
     * <li>{@code AUTHENTICATION_FAILED} — Token 无效或过期</li>
     * <li>{@code PERMISSION_DENIED} — 权限不足</li>
     * <li>{@code PROVIDER_TIMEOUT} — 下游 Provider 超时</li>
     * <li>{@code PROTOCOL_ERROR} — 内部协议错误</li>
     * </ul>
     *
     * @param baseUrl 待测试的网关基础 URL
     */
    private static void exampleErrorHandling(String baseUrl) {
        printSection("Example 4: Error Handling");

        // 使用无效 Token 触发鉴权错误。
        // 在 stub 模式下，stub AuthenticationPort 接受任意非空 Token，
        // 因此使用空串强制 AUTHENTICATION_FAILED。
        // 在 sa-token 模式下，任何未签名的 Token 都会触发 AUTHENTICATION_FAILED。
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

        // 使用不可达 URL 测试连接失败
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
     * 打印示例输出的分段标题。
     *
     * @param title 分段标题
     */
    private static void printSection(String title) {
        System.out.println("-".repeat(70));
        System.out.println(title);
        System.out.println("-".repeat(70));
    }
}
