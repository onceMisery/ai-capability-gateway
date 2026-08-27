package com.ai.gateway.adapter.a2a;

import java.net.URI;

/**
 * A2A 出站调用的传输出口（设计 §3.7）。
 *
 * <p>做成无状态的函数式接口，是为了从结构上满足 A2A SDK 的并发约束：<b>单个 A2A Client 实例
 * 不应被多线程共享</b>。把「发一次请求」表达成一次纯函数调用之后，适配器没有可持有的客户端
 * 会话对象，也就不存在跨请求复用同一个可变客户端的可能——这个约束不再依赖调用方自觉。</p>
 *
 * <p>实现方若内部持有连接池（如 JDK {@code HttpClient}），必须自证线程安全；
 * 若持有的是非线程安全的会话对象，则必须每次调用新建。</p>
 *
 * <p>实现方还负责<b>限制响应体大小</b>：字节数上限只能在真正读取字节的那一层生效，
 * 到了适配器手上响应体已经在内存里了。适配器只负责把过大的结果交给下游归一化层判定。</p>
 *
 * @author cmiracle@163.com
 * @since 0.1.0
 */
@FunctionalInterface
public interface A2aClientTransport {

    /**
     * 向远端 Agent 发送一次 JSON-RPC 请求。
     *
     * @param endpoint      远端 Agent 端点
     * @param jsonRpcBody   已序列化的 JSON-RPC 请求体
     * @param timeoutMillis 本次调用的剩余截止预算，恒为正数
     * @return 原始响应
     * @throws Exception 传输失败；适配器负责映射成稳定错误码，不透传实现方措辞
     */
    A2aClientResponse send(URI endpoint, String jsonRpcBody, long timeoutMillis) throws Exception;
}
