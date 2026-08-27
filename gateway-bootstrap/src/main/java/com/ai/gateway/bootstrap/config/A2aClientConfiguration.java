package com.ai.gateway.bootstrap.config;

import com.ai.gateway.adapter.a2a.A2aAgentEndpointResolver;
import com.ai.gateway.adapter.a2a.A2aClientTransport;
import com.ai.gateway.adapter.a2a.A2aInvocationAdapter;
import com.ai.gateway.adapter.a2a.A2aMode;
import com.ai.gateway.adapter.a2a.A2aTaskAuditRecorder;
import com.ai.gateway.adapter.a2a.JdkA2aClientTransport;
import com.ai.gateway.domain.port.ManifestRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

/**
 * A2A 出站客户端的装配（设计 §3.7）。
 *
 * <p>与入站装配（{@link A2aConfiguration}）分成两个门禁，而不是共用一个：入站是<b>暴露面</b>，
 * 出站是<b>依赖面</b>。一个只把远端 Agent 纳管成能力提供者的部署，不应该因此获得一个
 * 对外可达的 A2A 端点；反之，一个只做 A2A Server 的部署也不该持有出站客户端。
 * 两件事共用一个开关，早晚会让某个部署多出一个没人知道的方向。</p>
 *
 * <p>装配条件是 {@code enabled} 与 {@code mode.clientEnabled()} 的合取，判定逻辑与
 * {@link A2aConfiguration.ServerEnabledCondition} 同源——都委托给 {@link A2aMode#from(String)}，
 * 而不是在装配层再解析一次模式字符串。</p>
 *
 * @author cmiracle@163.com
 * @since 0.1.0
 * @see A2aInvocationAdapter
 */
@Configuration
@Conditional(A2aClientConfiguration.ClientEnabledCondition.class)
public class A2aClientConfiguration {

    /** 建连超时：读超时按每次调用的剩余截止预算下发，只有建连需要一个独立上限。 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

    /**
     * 远端 Agent 端点解析器。
     *
     * <p>解析不到就抛：一个解析不出地址的引用键是配置缺失，返回 {@code null} 只会把它推迟成
     * 调用时刻一个更难归因的空指针。同时拒绝非 {@code http/https} 地址——出站目标必须是
     * 一个明确的网络地址，而不是被某个 {@code URI} 实现宽容解释出来的东西。</p>
     *
     * @param properties 网关配置
     * @return 解析器
     */
    @Bean
    public A2aAgentEndpointResolver a2aAgentEndpointResolver(GatewayProperties properties) {
        Map<String, String> endpoints = properties.getProtocol().getA2aAgentEndpoints();
        return agentRef -> {
            String endpoint = endpoints.get(agentRef);
            if (endpoint == null || endpoint.isBlank()) {
                throw new IllegalArgumentException(
                        "A2A agent endpoint reference is not configured: " + agentRef);
            }
            URI uri = URI.create(endpoint.trim());
            String scheme = uri.getScheme();
            if (scheme == null
                    || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                throw new IllegalArgumentException(
                        "A2A agent endpoint must be an http(s) URL: " + agentRef);
            }
            return uri;
        };
    }

    /**
     * 出站传输出口。
     *
     * <p>响应体上限复用全局 {@code gateway.max-response-bytes}：远端 Agent 的返回值与
     * 其他协议的返回值在「多大算过大」这件事上没有理由不同，各自配一个键只会让两处逐渐偏移。</p>
     *
     * <p>不跟随重定向：一个被运维配置好的出站目标若能用 302 把网关引到别处，
     * 「出站目标由部署侧决定」这条边界就形同虚设。</p>
     *
     * @param properties 网关配置
     * @return 传输出口
     */
    @Bean
    public A2aClientTransport a2aClientTransport(GatewayProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        return new JdkA2aClientTransport(httpClient, properties.getMaxResponseBytes());
    }

    /**
     * 出站调用适配器。
     *
     * <p>它是一个普通的 {@code InvocationAdapter}，因此 {@code ProtocolRoutingInvocationAdapter}
     * 无需为 A2A 增加任何分支——路由表按各适配器自报的 {@code protocol()} 建立（开闭原则）。</p>
     *
     * <p>并发上限取 {@code gateway.a2a.client-max-concurrency}，并纳入既有舱壁之内：
     * 上游 {@code ResilientInvocationAdapter} 限制的是「网关同时在执行多少次调用」，
     * 这里限制的是「其中多少次可以同时压在远端 Agent 上」。两层各自回答一个问题，
     * 合并成一个数字就会让某个远端 Agent 变慢时拖垮全部协议的执行能力。</p>
     *
     * @param manifestRepository        已发布清单仓库
     * @param a2aAgentEndpointResolver  端点解析器
     * @param a2aClientTransport        传输出口
     * @param a2aTaskAuditRecorder      平面审计出口
     * @param objectMapper              JSON 编解码器
     * @param properties                网关配置
     * @return 出站适配器
     */
    @Bean
    public A2aInvocationAdapter a2aInvocationAdapter(
            ManifestRepository manifestRepository,
            A2aAgentEndpointResolver a2aAgentEndpointResolver,
            A2aClientTransport a2aClientTransport,
            A2aTaskAuditRecorder a2aTaskAuditRecorder,
            ObjectMapper objectMapper,
            GatewayProperties properties) {
        return new A2aInvocationAdapter(manifestRepository, a2aAgentEndpointResolver,
                a2aClientTransport, a2aTaskAuditRecorder, objectMapper,
                properties.getA2a().getClientMaxConcurrency());
    }

    /**
     * 「A2A 出站客户端是否装配」的判定。
     *
     * <p>与服务端判定并列而不是互斥：{@code FULL} 模式下两者都成立。</p>
     */
    static class ClientEnabledCondition implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            Environment environment = context.getEnvironment();
            return clientEnabled(environment.getProperty("gateway.a2a.enabled"),
                    environment.getProperty("gateway.a2a.mode"));
        }

        /**
         * @param enabled {@code gateway.a2a.enabled} 原始值，允许为 {@code null}
         * @param mode    {@code gateway.a2a.mode} 原始值，允许为 {@code null}
         * @return 是否装配出站客户端
         */
        static boolean clientEnabled(String enabled, String mode) {
            return clientEnabled(Boolean.parseBoolean(enabled), mode);
        }

        /**
         * @param enabled 总开关
         * @param mode    承载模式原始值，允许为 {@code null}
         * @return 是否装配出站客户端
         */
        static boolean clientEnabled(boolean enabled, String mode) {
            return enabled && A2aMode.from(mode).clientEnabled();
        }
    }
}

