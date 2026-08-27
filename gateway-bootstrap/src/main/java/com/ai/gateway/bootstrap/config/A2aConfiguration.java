package com.ai.gateway.bootstrap.config;

import com.ai.gateway.adapter.a2a.A2aDelegatedRetrievalHandler;
import com.ai.gateway.adapter.a2a.A2aExtendedCardProvider;
import com.ai.gateway.adapter.a2a.A2aGatewaySelectionRetrievalHandler;
import com.ai.gateway.adapter.a2a.A2aIdentityMode;
import com.ai.gateway.adapter.a2a.A2aJsonRpcDispatcher;
import com.ai.gateway.adapter.a2a.A2aMode;
import com.ai.gateway.adapter.a2a.A2aPeerTrustProfile;
import com.ai.gateway.adapter.a2a.A2aPeerTrustRegistry;
import com.ai.gateway.adapter.a2a.A2aPolicyEnforcementFilter;
import com.ai.gateway.adapter.a2a.A2aRateLimiter;
import com.ai.gateway.adapter.a2a.A2aRetrievalHandler;
import com.ai.gateway.adapter.a2a.A2aSelectionMode;
import com.ai.gateway.adapter.a2a.A2aServerTransportAdapter;
import com.ai.gateway.adapter.a2a.A2aTaskAuditRecorder;
import com.ai.gateway.adapter.a2a.A2aTaskStateMapper;
import com.ai.gateway.adapter.a2a.AgentCardCodec;
import com.ai.gateway.adapter.web.controller.A2aProtocolController;
import com.ai.gateway.adapter.web.support.RequestContextFactory;
import com.ai.gateway.application.agent.AgentCardProjectionService;
import com.ai.gateway.application.agent.AgentCardQueryUseCase;
import com.ai.gateway.application.agent.AgentHostConnector;
import com.ai.gateway.application.agent.CapabilityPublicProjectionService;
import com.ai.gateway.application.catalog.InMemoryCatalogManager;
import com.ai.gateway.application.resilience.RateLimiterManager;
import com.ai.gateway.application.runtime.NaturalLanguageQueryUseCase;
import com.ai.gateway.domain.model.TrustTier;
import com.ai.gateway.domain.port.AuthenticationPort;
import com.ai.gateway.domain.port.AuthorizationPort;
import com.ai.gateway.domain.port.TelemetryPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

/**
 * A2A 入站承载面的装配（设计 §3.9、§3.10）。
 *
 * <p>本类是 A2A 服务端的<b>唯一装配点</b>：策略执行点、检索策略、分级卡片投影、限流、审计
 * 以及两个 HTTP 端点全部在这里成型。把它单独成类而不是并入 {@link BeanConfig}，
 * 是因为「A2A 关闭时三个端点一律不注册」这条约束需要一个可以整体条件化的边界——
 * 逐个 Bean 挂条件注解，早晚会漏掉一个，而漏掉的那个恰好就是暴露面本身。</p>
 *
 * <p><b>装配条件是 {@code enabled} 与 {@code mode.serverEnabled()} 的合取。</b>
 * {@code @ConditionalOnProperty} 无法表达「模式枚举的某个语义属性为真」，因此这里用
 * {@link ServerEnabledCondition} 读原始配置并委托给 {@link A2aMode#from(String)}，
 * 而不是在装配层再解析一次模式字符串——解析两次就会出现「装配认为放行、适配器认为拒绝」
 * 这类只在生产才暴露的分歧。</p>
 *
 * <p>需要判定的纯逻辑（信任档案映射、检索策略选择、描述符构造、服务端启用判定）都提成
 * 静态或包可见方法，使它们可以在没有 Spring 容器的情况下被直接断言——
 * 本仓库既有的 {@code McpSecurityModeConfigurationTest} 就是这个风格。</p>
 *
 * @author cmiracle@163.com
 * @since 0.1.0
 * @see A2aServerTransportAdapter
 * @see ProductionConfigurationValidator
 */
@Configuration
@Conditional(A2aConfiguration.ServerEnabledCondition.class)
public class A2aConfiguration {

    /** 公开卡上的用途描述；措辞刻意不含任何内部拓扑或技术栈信息。 */
    private static final String AGENT_DESCRIPTION = "受治理的企业能力执行平面";

    /**
     * 取不到构件版本时公开卡使用的版本号。
     *
     * <p>版本刻意<b>不</b>做成配置键：一个可以手填的版本字段迟早与实际运行的构件不一致，
     * 而对端会拿它做兼容判断——一个静默偏移的版本号比没有版本号更糟。这里取运行中 jar
     * 清单里的实现版本，只有开发期（以类目录方式运行、没有清单）才退回本常量。</p>
     */
    private static final String FALLBACK_VERSION = "0.1.0";

    /**
     * 首跳选择模式：注册成 Bean 而不是在每个使用点各解析一次。
     *
     * <p>策略执行点、卡片编解码器与检索策略三者必须看到<b>同一个</b>档位。若各自调用
     * {@code from(...)}，一次配置热更新就可能让准入按 A 档判定、而卡片仍按 B 档宣告输入形态。</p>
     *
     * @param properties 网关配置
     * @return 归一化后的选择模式
     */
    @Bean
    public A2aSelectionMode a2aSelectionMode(GatewayProperties properties) {
        return A2aSelectionMode.from(properties.getA2a().getSelectionMode());
    }

    /**
     * @return A2A 任务状态映射器
     */
    @Bean
    public A2aTaskStateMapper a2aTaskStateMapper() {
        return new A2aTaskStateMapper();
    }

    /**
     * 卡片编解码器：把投影翻译成 A2A 线格。
     *
     * @param a2aSelectionMode 选择模式，决定卡片宣告的输入/输出形态
     * @return 编解码器
     */
    @Bean
    public AgentCardCodec agentCardCodec(A2aSelectionMode a2aSelectionMode) {
        return new AgentCardCodec(AgentCardCodec.TransportProfile.defaults(), a2aSelectionMode);
    }

    /**
     * 分级卡片投影服务。
     *
     * @param properties                        网关配置
     * @param capabilityPublicProjectionService 公开投影服务（提供注入检测与归一化）
     * @return 投影服务
     */
    @Bean
    public AgentCardProjectionService agentCardProjectionService(
            GatewayProperties properties,
            CapabilityPublicProjectionService capabilityPublicProjectionService) {
        return new AgentCardProjectionService(
                agentDescriptor(properties.getA2a()), capabilityPublicProjectionService);
    }

    /**
     * 构造网关自身的 Agent 描述符。
     *
     * <p>{@code publicUrl} 为空时 {@link AgentCardProjectionService.AgentDescriptor} 会直接拒绝
     * 构造：一张指向空地址的公开卡等于告诉对端「我在这里但你到不了」，
     * 这比不宣告更难排查。{@link ProductionConfigurationValidator} 会先给出可读的错误信息，
     * 这里的紧凑构造器是兜底而不是主防线。</p>
     *
     * @param a2a A2A 配置节点
     * @return Agent 描述符
     */
    static AgentCardProjectionService.AgentDescriptor agentDescriptor(GatewayProperties.A2a a2a) {
        return new AgentCardProjectionService.AgentDescriptor(
                a2a.getAgentName(), AGENT_DESCRIPTION, a2a.getPublicUrl(), gatewayVersion());
    }

    /**
     * 读取运行中构件的实现版本。
     *
     * @return 构件版本；无清单信息时为 {@link #FALLBACK_VERSION}
     */
    static String gatewayVersion() {
        Package pkg = A2aConfiguration.class.getPackage();
        String version = pkg == null ? null : pkg.getImplementationVersion();
        return version == null || version.isBlank() ? FALLBACK_VERSION : version;
    }

    /**
     * 分级卡片查询用例：承担认证、目录租约与策略纪元三重前置判定。
     *
     * @param authenticationPort         认证端口
     * @param authorizationPort          授权端口
     * @param inMemoryCatalogManager     运行面目录管理器
     * @param agentCardProjectionService 投影服务
     * @param telemetryPort              埋点端口
     * @return 查询用例
     */
    @Bean
    public AgentCardQueryUseCase agentCardQueryUseCase(
            AuthenticationPort authenticationPort,
            AuthorizationPort authorizationPort,
            InMemoryCatalogManager inMemoryCatalogManager,
            AgentCardProjectionService agentCardProjectionService,
            TelemetryPort telemetryPort) {
        return new AgentCardQueryUseCase(authenticationPort, authorizationPort,
                inMemoryCatalogManager, agentCardProjectionService, telemetryPort);
    }

    /**
     * 扩展卡提供者：直接以方法引用桥接查询用例。
     *
     * <p>刻意不写成带逻辑的 lambda。扩展卡的目录租约、策略纪元与失效关闭语义全在用例内部，
     * 这里每多一行判断，就多一条可能绕过租约、把已退役目录投影出去的路径。</p>
     *
     * @param agentCardQueryUseCase 分级卡片查询用例
     * @return 扩展卡提供者
     */
    @Bean
    public A2aExtendedCardProvider a2aExtendedCardProvider(
            AgentCardQueryUseCase agentCardQueryUseCase) {
        return agentCardQueryUseCase::extendedCard;
    }

    /**
     * peer 信任注册表。
     *
     * @param properties 网关配置
     * @return 注册表；未登记的 peer 恒落在只读一档
     */
    @Bean
    public A2aPeerTrustRegistry a2aPeerTrustRegistry(GatewayProperties properties) {
        GatewayProperties.A2a a2a = properties.getA2a();
        assertUnregisteredPeerIdentityMode(a2a.getIdentityMode());
        List<A2aPeerTrustProfile> profiles = a2a.getPeerTrust().stream()
                .map(A2aConfiguration::toPeerTrustProfile)
                .toList();
        return new A2aPeerTrustRegistry(profiles);
    }

    /**
     * 拒绝把 {@link A2aIdentityMode#SERVICE_ACCOUNT} 配成未注册 peer 的默认身份模式。
     *
     * <p>服务账号模式的成立条件是「一个固定租户号 + 一份显式能力白名单」，这两样只存在于
     * 具名的 {@code peer-trust} 档案里。若把它当成全局默认值，未注册 peer 就会落进一个
     * 没有租户归属的服务账号语义——那意味着一个匿名对端可以拿到不属于任何租户的执行身份。
     * 这与部署环境无关，因此在所有环境下硬失败。</p>
     *
     * <p>空值与无法识别的值同样落在此处：{@link A2aIdentityMode#from(String)} 的失效关闭方向
     * 是「更窄的一侧」即服务账号，而在<b>默认值</b>这个位置上，更窄反而是非法的。</p>
     *
     * @param configured {@code gateway.a2a.identity-mode} 的配置值
     * @throws IllegalStateException 配置值归一化为服务账号模式
     */
    static void assertUnregisteredPeerIdentityMode(String configured) {
        if (A2aIdentityMode.from(configured) == A2aIdentityMode.SERVICE_ACCOUNT) {
            throw new IllegalStateException("gateway.a2a.identity-mode must resolve to "
                    + "'ON_BEHALF_OF' (configured: '" + configured + "'): a service account "
                    + "requires a fixed tenant and an explicit capability allow-list, both of "
                    + "which only exist on a named gateway.a2a.peer-trust entry");
        }
    }

    /**
     * 把一条配置档案映射成信任档案。
     *
     * <p>任何一条读不懂的档案都让启动失败，而不是跳过。跳过的后果是运维以为某个 peer
     * 已经受信、实际它落在「未注册 ⇒ 只读」那一档——写操作会以一个看不出原因的拒绝告终，
     * 而配置文件里明明写着它被信任。</p>
     *
     * @param configured 配置档案，不得为 {@code null}
     * @return 信任档案
     * @throws IllegalStateException 档案字段非法或自相矛盾
     */
    static A2aPeerTrustProfile toPeerTrustProfile(GatewayProperties.A2aPeerTrust configured) {
        if (configured == null) {
            throw new IllegalStateException("gateway.a2a.peer-trust contains a null entry");
        }
        try {
            return new A2aPeerTrustProfile(
                    configured.getPeerId(),
                    configured.getTokenFingerprint(),
                    TrustTier.valueOf(normalized(configured.getTrustTier())),
                    A2aIdentityMode.from(configured.getIdentityMode()),
                    serviceAccountOrgId(configured.getServiceAccountOrgId()),
                    Set.copyOf(configured.getAllowedCapabilityIds()),
                    configured.getMaxDelegationDepth(),
                    configured.isEnabled(),
                    expiresAt(configured.getExpiresAt()));
        } catch (RuntimeException e) {
            throw new IllegalStateException("Invalid gateway.a2a.peer-trust entry: "
                    + configured.getPeerId(), e);
        }
    }

    /**
     * 配置层的租户号是字符串（用空串表达「未配置」），档案层要求 {@link Long}。
     *
     * <p>不做「解析失败就当没配」的宽松处理：一个写错的租户号若被吞成 {@code null}，
     * 服务账号档案的紧凑构造器只会说「缺租户号」，而真正的问题是那串数字打错了。</p>
     */
    private static Long serviceAccountOrgId(String configured) {
        return configured == null || configured.isBlank()
                ? null : Long.valueOf(configured.trim());
    }

    /** 空串表示不过期；其余一律按 ISO-8601 严格解析。 */
    private static Instant expiresAt(String configured) {
        return configured == null || configured.isBlank()
                ? null : Instant.parse(configured.trim());
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
    }

    /**
     * A2A 三个维度的独立限流器。
     *
     * <p>三个维度（公开卡、扩展卡、Task）各自独立，不与 MCP 或 REST 共享配额：
     * 匿名可达的公开卡被刷满时，受信 peer 的 Task 不该跟着一起被拒。</p>
     *
     * @param rateLimiterManager 限流管理器
     * @return A2A 限流器
     */
    @Bean
    public A2aRateLimiter a2aRateLimiter(RateLimiterManager rateLimiterManager) {
        return A2aRateLimiter.from(rateLimiterManager);
    }

    /**
     * 入站策略执行点：A2A 平面上唯一做准入判定的地方。
     *
     * <p>注入检测器传 {@code null} 表示采用内建规则集。这不是遗漏——把检测器做成可替换参数
     * 是为了让企业换成自有规则集，而装配期没有理由替换默认值；写死一个新实例反而会让
     * 「内建规则」出现两份来源。</p>
     *
     * @param properties           网关配置
     * @param a2aPeerTrustRegistry 信任注册表
     * @param a2aRateLimiter       限流器
     * @param a2aSelectionMode     选择模式
     * @param telemetryPort        埋点端口
     * @return 策略执行点
     */
    @Bean
    public A2aPolicyEnforcementFilter a2aPolicyEnforcementFilter(
            GatewayProperties properties,
            A2aPeerTrustRegistry a2aPeerTrustRegistry,
            A2aRateLimiter a2aRateLimiter,
            A2aSelectionMode a2aSelectionMode,
            TelemetryPort telemetryPort) {
        return new A2aPolicyEnforcementFilter(a2aPeerTrustRegistry, a2aRateLimiter,
                null, a2aSelectionMode,
                properties.getA2a().getMaxDelegationDepth(), telemetryPort);
    }

    /**
     * 按选择模式装配首跳检索策略。
     *
     * <p>{@link NaturalLanguageQueryUseCase} 走 {@link ObjectProvider} 惰性取用：
     * 只有 {@link A2aSelectionMode#GATEWAY_SELECTION} 一档需要 NL 内核，其余两档不该因为
     * 「诊断用的 LLM 链路缺一个 Bean」而连带无法启用 A2A 入站。</p>
     *
     * @param properties                 网关配置
     * @param a2aSelectionMode           选择模式
     * @param a2aTaskStateMapper         状态映射器
     * @param agentHostConnector         执行链连接器
     * @param naturalLanguageQueryUseCase NL 路由用例提供者
     * @return 检索策略
     */
    @Bean
    public A2aRetrievalHandler a2aRetrievalHandler(
            GatewayProperties properties,
            A2aSelectionMode a2aSelectionMode,
            A2aTaskStateMapper a2aTaskStateMapper,
            AgentHostConnector agentHostConnector,
            ObjectProvider<NaturalLanguageQueryUseCase> naturalLanguageQueryUseCase) {
        return retrievalHandler(properties.getA2a(), a2aSelectionMode, a2aTaskStateMapper,
                agentHostConnector, naturalLanguageQueryUseCase::getObject);
    }

    /**
     * 选择模式到检索策略的映射。
     *
     * <p>写成 {@code switch} 表达式而不是 {@code if} 链：新增一档选择模式时编译器会在这里
     * 报缺分支，而 {@code if} 链只会静默走到 {@code else}。</p>
     *
     * @param a2a           A2A 配置节点
     * @param selectionMode 选择模式
     * @param stateMapper   状态映射器
     * @param connector     执行链连接器
     * @param nlRouter      NL 路由用例的惰性取用；仅网关选择档会被调用
     * @return 检索策略
     */
    static A2aRetrievalHandler retrievalHandler(
            GatewayProperties.A2a a2a,
            A2aSelectionMode selectionMode,
            A2aTaskStateMapper stateMapper,
            AgentHostConnector connector,
            Supplier<NaturalLanguageQueryUseCase> nlRouter) {
        return switch (selectionMode) {
            case DELEGATED_SELECTION -> new A2aDelegatedRetrievalHandler(
                    connector, stateMapper, a2a.getCandidateTopK());
            case GATEWAY_SELECTION -> new A2aGatewaySelectionRetrievalHandler(
                    nlRouter.get(), stateMapper, a2a.getLocale(), a2a.getTimezone());
            case STRUCTURED_ONLY -> A2aRetrievalHandler.rejecting(stateMapper);
        };
    }

    /**
     * A2A 入站传输适配器：卡片与 Task 三个端点的行为都在这里成型。
     *
     * @param properties                  网关配置
     * @param a2aPolicyEnforcementFilter  策略执行点
     * @param a2aTaskStateMapper          状态映射器
     * @param agentHostConnector          执行链连接器
     * @param a2aRetrievalHandler         首跳检索策略
     * @param agentCardProjectionService  卡片投影服务
     * @param agentCardCodec              卡片编解码器
     * @param a2aExtendedCardProvider     扩展卡提供者
     * @param a2aTaskAuditRecorder        审计记录器
     * @return 入站传输适配器
     */
    @Bean
    public A2aServerTransportAdapter a2aServerTransportAdapter(
            GatewayProperties properties,
            A2aPolicyEnforcementFilter a2aPolicyEnforcementFilter,
            A2aTaskStateMapper a2aTaskStateMapper,
            AgentHostConnector agentHostConnector,
            A2aRetrievalHandler a2aRetrievalHandler,
            AgentCardProjectionService agentCardProjectionService,
            AgentCardCodec agentCardCodec,
            A2aExtendedCardProvider a2aExtendedCardProvider,
            A2aTaskAuditRecorder a2aTaskAuditRecorder) {
        return new A2aServerTransportAdapter(a2aPolicyEnforcementFilter, a2aTaskStateMapper,
                agentHostConnector, a2aRetrievalHandler, agentCardProjectionService,
                agentCardCodec, a2aExtendedCardProvider, a2aTaskAuditRecorder,
                properties.getA2a().getLocale());
    }

    /**
     * JSON-RPC 协议分发器。
     *
     * @param a2aServerTransportAdapter 入站传输适配器
     * @return 分发器
     */
    @Bean
    public A2aJsonRpcDispatcher a2aJsonRpcDispatcher(
            A2aServerTransportAdapter a2aServerTransportAdapter) {
        return new A2aJsonRpcDispatcher(a2aServerTransportAdapter);
    }

    /**
     * HTTP 承载面控制器。
     *
     * <p>控制器<b>不</b>带 {@code @Controller} 注解、也不在 {@code WebAdaptersConfiguration}
     * 的导入清单里，而是在这里声明成 Bean：只有这样「A2A 关闭 ⇒ 两个 HTTP 端点根本不存在」
     * 才是装配的直接结果，而不是依赖某个条件注解恰好也生效。被组件扫描顺带注册的控制器
     * 会在 A2A 关闭时仍然可达，然后在缺少分发器时以启动失败或 500 告终。</p>
     *
     * @param a2aJsonRpcDispatcher 协议分发器
     * @param requestContextFactory 请求上下文工厂
     * @return 控制器
     */
    @Bean
    public A2aProtocolController a2aProtocolController(
            A2aJsonRpcDispatcher a2aJsonRpcDispatcher,
            RequestContextFactory requestContextFactory) {
        return new A2aProtocolController(a2aJsonRpcDispatcher, requestContextFactory);
    }

    /**
     * 「A2A 服务端是否装配」的判定。
     *
     * <p>两个条件的合取：总开关打开，且承载模式包含服务端职责。分开表达而不是合成一个键，
     * 是因为它们回答的是不同的问题——{@code enabled} 是「这套能力是否投入使用」，
     * {@code mode} 是「投入使用时承担入站还是出站」。只开出站的部署必须拿不到入站端点。</p>
     */
    static class ServerEnabledCondition implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            Environment environment = context.getEnvironment();
            return serverEnabled(environment.getProperty("gateway.a2a.enabled"),
                    environment.getProperty("gateway.a2a.mode"));
        }

        /**
         * @param enabled {@code gateway.a2a.enabled} 原始值，允许为 {@code null}
         * @param mode    {@code gateway.a2a.mode} 原始值，允许为 {@code null}
         * @return 是否装配服务端
         */
        static boolean serverEnabled(String enabled, String mode) {
            return serverEnabled(Boolean.parseBoolean(enabled), mode);
        }

        /**
         * 供已绑定好的 {@link GatewayProperties} 复用同一条判定。
         *
         * <p>限流规则的注册也必须与本判定一致（见
         * {@code SentinelRateLimitConfiguration#a2aDimensionQps}）：若两边各自判断一次，
         * 就会出现「端点装配了但规则没注册」这种只在压测时才暴露的组合。</p>
         *
         * @param enabled 总开关
         * @param mode    承载模式原始值，允许为 {@code null}
         * @return 是否装配服务端
         */
        static boolean serverEnabled(boolean enabled, String mode) {
            return enabled && A2aMode.from(mode).serverEnabled();
        }
    }
}
