package com.ai.gateway.domain.model;

import java.util.List;
import java.util.Map;

/**
 * 能力的确定性协议调用绑定。
 *
 * <p>将协议绑定定义为<strong>不</strong>进入模型上下文的执行配置。它包含执行泛化调用
 * 所需的全部信息，且运行期无需加载任何业务 API JAR。</p>
 *
 * <p>对于 Dubbo（第 12 节），绑定包含：</p>
 * <ul>
 * <li>{@code registryRef} - 引用运维预配置的注册中心；清单不得携带用户名、密码或
 * 任意注册中心地址。</li>
 * <li>{@code interfaceName}、{@code group}、{@code version}、{@code method} -
 * Dubbo 服务坐标。</li>
 * <li>{@code parameterTypes} - 必须与 {@code arguments} 位置及 {@code protocolType}
 * 值一一对应。</li>
 * <li>{@code serialization} - 必须属于平台序列化白名单，并在兼容性测试中校验。</li>
 * <li>{@code arguments} - 按序的参数绑定。</li>
 * <li>{@code attachments} - 基于白名单键的附件绑定。</li>
 * </ul>
 *
 * <p>网关在编译或运行期都不会加载 {@code interfaceName} 或 {@code parameterTypes} 类；
 * 它们仅作为类型名字符串用于泛化调用。</p>
 *
 * @param protocol 线缆协议
 * @param registryRef 运维预配置的注册中心引用
 * @param interfaceName 全限定服务接口名
 * @param group 服务分组
 * @param version 服务版本
 * @param method 待调用的方法名
 * @param parameterTypes 全限定参数类型名列表
 * @param serialization 序列化方式（须位于平台白名单）
 * @param arguments 按序的参数绑定
 * @param attachments 以白名单附件名为键的附件绑定
 * @since 0.1.0
 */
public record ProtocolBinding(
        Protocol protocol,
        String registryRef,
        String interfaceName,
        String group,
        String version,
        String method,
        List<String> parameterTypes,
        String serialization,
        List<ArgumentBinding> arguments,
        Map<String, AttachmentBinding> attachments
) {

    /**
     * 紧凑构造器，执行防御性拷贝。
     *
     * @param protocol 线缆协议
     * @param registryRef 注册中心引用
     * @param interfaceName 服务接口名
     * @param group 服务分组
     * @param version 服务版本
     * @param method 方法名
     * @param parameterTypes 参数类型名
     * @param serialization 序列化方式
     * @param arguments 参数绑定
     * @param attachments 附件绑定
     */
    public ProtocolBinding {
        java.util.Objects.requireNonNull(protocol, "protocol must not be null");
        java.util.Objects.requireNonNull(interfaceName, "interfaceName must not be null");
        java.util.Objects.requireNonNull(method, "method must not be null");
        java.util.Objects.requireNonNull(parameterTypes, "parameterTypes must not be null");
        java.util.Objects.requireNonNull(arguments, "arguments must not be null");
        parameterTypes = List.copyOf(parameterTypes);
        arguments = List.copyOf(arguments);
        if (attachments != null) {
            attachments = Map.copyOf(attachments);
        }
    }
}
