package com.ai.gateway.domain.port;

import com.ai.gateway.domain.model.InvocationRequest;
import com.ai.gateway.domain.model.InvocationResult;
import com.ai.gateway.domain.model.Protocol;
import com.ai.gateway.domain.model.ProtocolBinding;
import com.ai.gateway.domain.model.ValidationReport;

/**
 * 统一的协议调用适配器端口。
 *
 * <p>规定所有协议都实现同一应用层端口。适配器不得执行自然语言路由、用户鉴权或能力
 * 状态变更。</p>
 *
 * <p>初始生产版本仅支持 {@link Protocol#DUBBO Dubbo}。{@link Protocol#REST REST} 与
 * {@link Protocol#GRPC gRPC} 为演进协议（第 14 节）。所有协议共享相同的生命周期、确认
 * 流程、自然语言语义、入参/出参 JSON Schema、Principal 注入、鉴权、风险、审计与写操作
 * 状态机。协议差异仅存在于 {@code spec.invocation} 与适配器内部。</p>
 *
 * <p>中性请求（{@link InvocationRequest}）包含能力标识、截止预算、幂等键、追踪上下文，
 * 以及完整绑定、按位置排序的协议参数。适配器不得执行 NL 路由、用户鉴权或能力状态变更。</p>
 *
 * <p>中性结果（{@link InvocationResult}）仅包含 JSON 兼容数据、协议状态、稳定错误码、
 * 错误消息与调用元数据。不包含原始协议对象、堆栈、内部地址、接口类名或敏感参数。</p>
 *
 * <p>实现此端口的适配器负责特定协议的泛化调用。该端口是纯粹的领域抽象，不依赖任何
 * 框架。</p>
 *
 * @see Protocol
 * @see ProtocolBinding
 * @see ValidationReport
 * @see InvocationRequest
 * @see InvocationResult
 * @since 0.1.0
 */
public interface InvocationAdapter {

    /**
     * 返回该适配器处理的线缆协议。
     *
     * <p>规定：每个适配器实现精确服务于一种协议。网关依据能力清单中声明的
     * {@link ProtocolBinding#protocol()} 选择合适的适配器。</p>
     *
     * @return 线缆协议；永不为 {@code null}
     */
    Protocol protocol();

    /**
     * 校验协议绑定的结构、语义与安全合规性。
     *
     * <p>规定：适配器在将绑定配置用于调用之前先行校验。这是 10 步校验流水线的一部分。</p>
     *
     * <p>对于 Dubbo（第 12 节），校验包括确认 {@code parameterTypes} 与参数位置一一对应、
     * {@code serialization} 属于平台白名单，且 {@code registryRef} 引用一个预配置的
     * 注册中心。</p>
     *
     * @param binding 待校验的协议绑定
     * @return 校验报告；仅当 {@code errors} 为空时才有效
     */
    ValidationReport validate(ProtocolBinding binding);

    /**
     * 使用完整绑定的请求调用目标能力。
     *
     * <p>规定：中性请求包含能力标识、截止预算、幂等键、追踪上下文，以及按序完整绑定的
     * 协议参数。适配器将协议结果转换为 JSON 兼容树。</p>
     *
     * <p>规定适配器返回后的结果处理顺序：适配器将协议结果转为 JSON，随后网关检查响应
     * 大小/深度/集合长度，应用信封规则、投影白名单、字段脱敏，并校验公开出参 Schema。</p>
     *
     * @param request 协议无关调用请求
     * @return 协议无关调用结果；永不为 {@code null}
     */
    InvocationResult invoke(InvocationRequest request);
}
