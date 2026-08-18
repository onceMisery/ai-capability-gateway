package com.ai.gateway.adapter.dubbo;

import java.util.Collections;
import java.util.Set;

/**
 * Dubbo 泛化调用的序列化白名单。
 *
 * <p>序列化方式是 Manifest 协议绑定中声明的能力级配置。声明的值必须属于
 * 该平台维护的白名单。只允许 Apache Dubbo 社区版本自带的序列化实现。</p>
 *
 * <p>当前白名单包含：</p>
 * <ul>
 * <li>{@code hessian2} - Apache Dubbo 默认的 Hessian2 序列化</li>
 * <li>{@code fastjson2} - Fastjson2 序列化</li>
 * </ul>
 *
 * <p>内部自定义序列化扩展（私有构件中的自定义序列化 ID）不会进入白名单：
 * 引入它们需要依赖内部 JAR，违反独立性边界（第 4 节）。如果目标服务仅支持内部
 * 自定义序列化，必须先暴露标准的序列化协商能力，之后才能接入。</p>
 *
 * @author cmiracle@163.com
 * @since 0.1.0
 */
public final class SerializationWhitelist {

    /**
     * 允许的序列化方式的不可变集合。
     */
    private static final Set<String> ALLOWED = Collections.unmodifiableSet(
            Set.of("hessian2", "fastjson2"));

    private SerializationWhitelist() {
        // 工具类——不可实例化
    }

    /**
     * 返回给定的序列化方式是否在白名单中。
     *
     * @param serialization 待检查的序列化方式名称
     * @return {@code true} 表示该序列化方式在白名单内
     */
    public static boolean isAllowed(String serialization) {
        if (serialization == null) {
            return false;
        }
        return ALLOWED.contains(serialization);
    }

    /**
     * 校验给定的序列化方式是否在白名单中。
     *
     * @param serialization 待校验的序列化方式名称
     * @throws IllegalArgumentException 如果序列化方式不在白名单中
     * @throws NullPointerException 如果 serialization 为 null
     */
    public static void validate(String serialization) {
        java.util.Objects.requireNonNull(serialization,
                "serialization must not be null");
        if (!ALLOWED.contains(serialization)) {
            throw new IllegalArgumentException(
                    "Serialization method '" + serialization
                            + "' is not in the platform whitelist. "
                            + "Allowed: " + ALLOWED
                            + "");
        }
    }

    /**
     * 返回允许的序列化方式的不可修改视图。
     *
     * @return 白名单内的序列化方式名称集合
     */
    public static Set<String> allowedValues() {
        return ALLOWED;
    }
}
