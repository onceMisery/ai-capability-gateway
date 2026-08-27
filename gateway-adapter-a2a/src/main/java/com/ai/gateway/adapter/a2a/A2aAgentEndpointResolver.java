package com.ai.gateway.adapter.a2a;

import java.net.URI;

/**
 * 把清单里的远端 Agent 引用键解析成实际可达地址（设计 §3.7）。
 *
 * <p>做成一个由装配期实现的接口，而不是让适配器直接读配置或读注册中心，理由与
 * {@code RestEndpointResolver} 相同：<b>出站目标是运维边界</b>。清单只携带一个引用键，
 * 地址、端口、是否走 TLS 一律由部署侧决定；这样一份能力清单在测试环境与生产环境可以逐字节相同，
 * 而「导入清单」这个动作永远不可能给网关新增一个它本来到不了的出站目标。</p>
 *
 * <p>解析不到时应当<b>抛出</b>而不是返回 {@code null}：一个解析不出地址的引用键是配置缺失，
 * 而返回 {@code null} 只会把它推迟成调用时刻一个更难归因的空指针。</p>
 *
 * @author cmiracle@163.com
 * @since 0.1.0
 */
@FunctionalInterface
public interface A2aAgentEndpointResolver {

    /**
     * 解析远端 Agent 的 A2A 端点地址。
     *
     * @param agentRef 运维预配置的引用键，取自 {@code ProtocolBinding.registryRef()}
     * @return 远端 Agent 的 JSON-RPC 端点地址
     * @throws IllegalArgumentException 引用键未配置
     */
    URI resolve(String agentRef);
}
