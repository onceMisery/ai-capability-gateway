package com.ai.gateway.bootstrap.config;

import com.ai.gateway.domain.model.NlRouterMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 当部署环境不明确，或生产部署仍沿用开发期默认配置时，
 * 使应用启动失败。
 *
 * @author cmiracle@163.com
 */
@Component
public final class ProductionConfigurationValidator implements InitializingBean {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(ProductionConfigurationValidator.class);

    private static final Set<String> ALLOWED_ENVIRONMENTS =
            Set.of("development", "test", "staging", "production");
    private static final int MIN_SECRET_BYTES = 32;
    private static final String NL_ROUTER_MODE_KEY = "gateway.runtime.nl-router.mode";
    private static final String NL_ROUTER_DIAGNOSTICS_ENABLED_KEY =
            "gateway.runtime.nl-router.diagnostics-enabled";
    /**
     * A2A 入站选择档位配置键。
     *
     * <p>该配置由 P1 的 {@code gateway-adapter-a2a} 模块引入；此处提前校验其与
     * 自然语言路由模式的组合，使「先配 A2A、后关 LLM」这类顺序造成的非法组合
     * 在启动期即被拦下，而不必等 A2A 模块落地后再补校验。</p>
     */
    private static final String A2A_SELECTION_MODE_KEY = "gateway.a2a.selection-mode";
    private static final String A2A_MODE_KEY = "gateway.a2a.mode";
    private static final String A2A_PUBLIC_URL_KEY = "gateway.a2a.public-url";
    private static final String A2A_EXTENDED_CARD_REQUIRED_KEY =
            "gateway.a2a.extended-card-required";
    private static final String A2A_ALLOW_GATEWAY_SELECTION_KEY =
            "gateway.a2a.allow-gateway-selection-in-production";
    private static final String GATEWAY_SELECTION = "GATEWAY_SELECTION";
    private static final String A2A_DISABLED = "DISABLED";

    private final Environment environment;

    public ProductionConfigurationValidator(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        validate(environment);
    }

    static void validate(Environment environment) {
        String deploymentEnvironment = value(environment, "gateway.environment");
        if (deploymentEnvironment.isBlank()) {
            throw new IllegalStateException(
                    "gateway.environment must be explicitly configured");
        }

        String normalized = deploymentEnvironment.toLowerCase(Locale.ROOT);
        if (!ALLOWED_ENVIRONMENTS.contains(normalized)) {
            throw new IllegalStateException(
                    "gateway.environment must be one of " + ALLOWED_ENVIRONMENTS);
        }
        // 自然语言路由模式在所有环境都必须可解析：模式拼错会静默退化成默认档，
        // 使运行面在本应关闭的部署上继续曝光，属安全相关的配置错误。
        NlRouterMode routerMode = requireValidNlRouterMode(environment);
        validateNlRouterCombinations(environment, routerMode);
        // A2A 的曝光面校验同样与环境无关：一张公开卡若指向空地址，对端拿到的是一张
        // 无法回连的卡，而这类错误只会在对端首次接入时才暴露。
        validateA2aExposure(environment);
        if (!"production".equals(normalized)) {
            return;
        }

        List<String> violations = new ArrayList<>();
        requireEquals(environment, violations, "gateway.auth.provider", "sa-token");
        requireEquals(environment, violations, "gateway.cache.provider", "redis");
        requireEquals(environment, violations, "gateway.ratelimit.provider", "sentinel");

        requireNonBlank(environment, violations, "spring.datasource.url");
        requireNonBlank(environment, violations, "spring.datasource.username");
        requireNonBlank(environment, violations, "spring.datasource.password");
        requireNonBlank(environment, violations, "gateway.redis.address");
        // LLM 凭据只在「内核会被使用」时强制：DISABLED 档下运行面与诊断面都关闭，
        // 此时强制凭据会让「不需要模型的最小部署」无法上线。
        if (routerMode.llmKernelLoaded()) {
            requireNonBlank(environment, violations, "gateway.llm.endpoint");
            requireNonBlank(environment, violations, "gateway.llm.api-key");
            requireNonBlank(environment, violations, "gateway.llm.model");
        }
        requireNonBlank(environment, violations, "dubbo.registry.address");

        String datasourceUrl = value(environment, "spring.datasource.url");
        if (!datasourceUrl.startsWith("jdbc:postgresql:") || datasourceUrl.endsWith("://")) {
            violations.add("spring.datasource.url must be a concrete PostgreSQL JDBC URL");
        }

        requireStrongSecret(environment, violations,
                "gateway.auth.sa-token.jwt-secret-key");
        requireStrongSecret(environment, violations,
                "gateway.operation.confirmation-secret");
        requireStrongSecret(environment, violations,
                "gateway.agent.tool-ref-secret");
        validateA2aProductionCombinations(environment, violations);

        String adminUsername = value(environment, "gateway.auth.console-admin.username");
        String adminPassword = value(environment, "gateway.auth.console-admin.password");
        if (adminUsername.isBlank() || adminPassword.isBlank()
                || "admin".equalsIgnoreCase(adminPassword)
                || "change-me".equalsIgnoreCase(adminPassword)
                || adminPassword.length() < 12) {
            violations.add("gateway.auth.console-admin username/password must be explicitly "
                    + "configured and the password must contain at least 12 characters");
        }

        if (!violations.isEmpty()) {
            throw new IllegalStateException(
                    "Unsafe production configuration: " + String.join("; ", violations));
        }
    }

    /**
     * 解析并校验 {@code gateway.runtime.nl-router.mode}。
     *
     * <p>该校验对所有环境生效而非仅生产：模式拼错在任何环境都会静默回落到默认档，
     * 使一个「本应关闭运行面」的部署继续对外曝光自然语言入口——这属于曝光面错误，
     * 与环境无关。</p>
     *
     * @param environment Spring 环境
     * @return 解析出的模式
     * @throws IllegalStateException 取值不在 {@link NlRouterMode} 范围内时抛出
     */
    private static NlRouterMode requireValidNlRouterMode(Environment environment) {
        String rawMode = value(environment, NL_ROUTER_MODE_KEY);
        try {
            return NlRouterMode.parse(rawMode);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(NL_ROUTER_MODE_KEY + " must be one of "
                    + java.util.Arrays.toString(NlRouterMode.values())
                    + " but was '" + rawMode + "'", e);
        }
    }

    /**
     * 校验自然语言路由模式与其它开关的组合合法性。
     *
     * <p>两类组合被区别对待，判据是「继续启动是否会造成安全或语义错误」：</p>
     * <ul>
     * <li><b>硬失败</b>：{@code DISABLED} 档下 A2A 入站仍选择 {@code GATEWAY_SELECTION}。
     * 该档位要求网关自己跑受限选择，而 LLM 内核在 DISABLED 下不装配，
     * 运行期必然在每次入站请求上失败。让它在启动期失败，而不是在流量上失败。</li>
     * <li><b>降级并告警</b>：{@code DISABLED} 档下仍显式开启管理面诊断。
     * 归一化本身已由 {@code NlRouterMode.diagnosticsCapable()} 与
     * {@code NlRouterPolicy} 在同一处完成，此处不重复判定、只提示配置无效，
     * 避免运维误以为诊断端点可用。</li>
     * </ul>
     *
     * @param environment Spring 环境
     * @param routerMode  已解析的模式
     */
    private static void validateNlRouterCombinations(Environment environment,
                                                     NlRouterMode routerMode) {
        if (routerMode.llmKernelLoaded()) {
            return;
        }
        String a2aSelectionMode = value(environment, A2A_SELECTION_MODE_KEY);
        if (GATEWAY_SELECTION.equalsIgnoreCase(a2aSelectionMode)) {
            throw new IllegalStateException(A2A_SELECTION_MODE_KEY + " must not be '"
                    + GATEWAY_SELECTION + "' when " + NL_ROUTER_MODE_KEY + " is "
                    + NlRouterMode.DISABLED + ": gateway-side restricted selection requires "
                    + "the LLM kernel");
        }
        if (Boolean.parseBoolean(value(environment, NL_ROUTER_DIAGNOSTICS_ENABLED_KEY))) {
            LOGGER.warn("{}=true is ignored because {} is {}; catalog diagnostics stays "
                            + "unavailable on this deployment",
                    NL_ROUTER_DIAGNOSTICS_ENABLED_KEY, NL_ROUTER_MODE_KEY, routerMode);
        }
    }

    /**
     * 校验 A2A 承载面的曝光配置，与部署环境无关。
     *
     * <p>只校验一条：{@code mode != DISABLED} 时 {@code public-url} 必填。公开卡上的地址是
     * 对端回连网关的唯一依据，留空会让一张语法完整、语义无效的卡被匿名分发出去，
     * 而这个错误直到某个 peer 首次尝试接入才会显形。</p>
     *
     * @param environment Spring 环境
     * @throws IllegalStateException 承载模式已开启但未配置公开地址时抛出
     */
    private static void validateA2aExposure(Environment environment) {
        String mode = value(environment, A2A_MODE_KEY);
        if (mode.isBlank() || A2A_DISABLED.equalsIgnoreCase(mode)) {
            return;
        }
        if (value(environment, A2A_PUBLIC_URL_KEY).isBlank()) {
            throw new IllegalStateException(A2A_PUBLIC_URL_KEY + " is required when "
                    + A2A_MODE_KEY + " is '" + mode + "': the public card must advertise a "
                    + "reachable address");
        }
    }

    /**
     * 校验 A2A 在生产环境下的两条额外约束。
     *
     * <p>两条约束的性质不同，因此都收进 {@code violations} 一次性报出，而不是各自抛异常：</p>
     * <ul>
     * <li>{@code extended-card-required=false} 会让<b>匿名</b>调用方拿到带 {@code skills} 的卡，
     * 等于把能力目录的域粒度对外公开。这是曝光面缺陷，生产环境直接拒绝启动。</li>
     * <li>{@code selection-mode=GATEWAY_SELECTION} 把选择责任交回网关侧的模型推理，
     * 使入站流量消耗模型额度并依赖模型可用性。它在生产可用，但必须被显式承担，
     * 不能从兼容默认值继承而来。</li>
     * </ul>
     *
     * @param environment Spring 环境
     * @param violations  违规收集器
     */
    private static void validateA2aProductionCombinations(Environment environment,
                                                         List<String> violations) {
        String mode = value(environment, A2A_MODE_KEY);
        if (mode.isBlank() || A2A_DISABLED.equalsIgnoreCase(mode)) {
            return;
        }
        String extendedCardRequired = value(environment, A2A_EXTENDED_CARD_REQUIRED_KEY);
        if (!extendedCardRequired.isBlank() && !Boolean.parseBoolean(extendedCardRequired)) {
            violations.add(A2A_EXTENDED_CARD_REQUIRED_KEY + " must not be false in production: "
                    + "an unauthenticated extended card would expose the capability catalog");
        }
        if (GATEWAY_SELECTION.equalsIgnoreCase(value(environment, A2A_SELECTION_MODE_KEY))
                && !Boolean.parseBoolean(value(environment, A2A_ALLOW_GATEWAY_SELECTION_KEY))) {
            violations.add(A2A_SELECTION_MODE_KEY + "='" + GATEWAY_SELECTION + "' requires "
                    + A2A_ALLOW_GATEWAY_SELECTION_KEY + "=true in production: inbound traffic "
                    + "would consume model quota on every hop");
        }
    }

    private static void requireEquals(Environment environment, List<String> violations,
                                      String key, String expected) {
        String actual = value(environment, key);
        if (!expected.equalsIgnoreCase(actual)) {
            violations.add(key + " must be '" + expected + "'");
        }
    }

    private static void requireNonBlank(Environment environment, List<String> violations,
                                        String key) {
        if (value(environment, key).isBlank()) {
            violations.add(key + " is required");
        }
    }

    private static void requireStrongSecret(Environment environment, List<String> violations,
                                            String key) {
        String secret = value(environment, key);
        String normalized = secret.toLowerCase(Locale.ROOT);
        if (secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES
                || normalized.contains("dev-only")
                || normalized.contains("change-me")) {
            violations.add(key + " must contain at least " + MIN_SECRET_BYTES
                    + " bytes and must not use a development default");
        }
    }

    private static String value(Environment environment, String key) {
        String value = environment.getProperty(key);
        return value == null ? "" : value.trim();
    }
}
