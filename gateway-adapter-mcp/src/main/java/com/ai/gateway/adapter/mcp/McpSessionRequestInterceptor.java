package com.ai.gateway.adapter.mcp;

import com.ai.gateway.domain.model.RequestContext;

/**
 * 会话级 JSON-RPC 请求拦截器。
 *
 * <p>存在的原因是 MCP SDK 高层服务的工具面是<b>静态且服务端全局</b>的：工具在
 * {@code McpServer} 构建期一次性注册，{@code tools/list} 对所有会话返回同一份清单，
 * {@code tools/call} 也只按已注册的精确工具名分发。而按会话身份投影的工具面天然是
 * 「每会话不同」的，且工具名是运行期生成的 alias，两者无法通过静态注册表达。</p>
 *
 * <p>因此传输层在把消息交给 SDK 会话之前，先给拦截器一次机会：拦截器返回非 {@code null}
 * 即由它作答，返回 {@code null} 则原路交还 SDK。这样既不需要 fork SDK，也不影响未被
 * 拦截的方法（{@code initialize}、{@code ping} 等）——新增一种曝光模式不必改动传输层
 * 与 SDK 装配（开闭原则）。</p>
 *
 * <p>实现必须是线程安全的：同一实例被所有会话共享。</p>
 *
 * @author cmiracle@163.com
 * @since 0.1.0
 */
@FunctionalInterface
public interface McpSessionRequestInterceptor {

    /** 不拦截任何请求的空实现。 */
    static McpSessionRequestInterceptor none() {
        return (method, params, context, deadlineNanos) -> null;
    }

    /**
     * 尝试处理一个 JSON-RPC 请求。
     *
     * @param method        JSON-RPC 方法名
     * @param params        原始参数对象（通常是已反序列化的 {@code Map}），可为 {@code null}
     * @param context       已认证的请求上下文
     * @param deadlineNanos 本次调用的绝对截止时间（{@code System.nanoTime()} 基准）
     * @return 作为 JSON-RPC {@code result} 回送的对象；{@code null} 表示不拦截
     */
    Object intercept(String method, Object params, RequestContext context, long deadlineNanos);
}
