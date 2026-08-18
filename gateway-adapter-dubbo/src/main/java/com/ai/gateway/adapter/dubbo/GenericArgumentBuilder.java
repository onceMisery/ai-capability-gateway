package com.ai.gateway.adapter.dubbo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 为 Dubbo 构建泛化调用参数。
 *
 * <p>Dubbo 泛化调用使用 Map/List/基本类型结构，而非加载业务 API JAR。对于 POJO
 * 参数类型，适配器会创建一个 {@link HashMap}，其中 {@code class} 字段设置为
 * Manifest 中确认的 {@code protocolType}。用户和模型不得写入 {@code class} 或
 * {@code @type} 字段——适配器仅从确认的协议类型生成类型元数据。</p>
 *
 * <p>方法签名如下：</p>
 * <pre>
 * Object[] buildArguments(List&lt;Object&gt; boundArguments, List&lt;String&gt; parameterTypes)
 * </pre>
 *
 * <p>其中 {@code boundArguments} 是已完全解析、按位置排序的协议参数，
 * {@code parameterTypes} 是接口声明中精确的类型名（字符串）。适配器不会调用
 * {@code Class.forName}。</p>
 *
 * @author cmiracle@163.com
 * @since 0.1.0
 */
@Component
public class GenericArgumentBuilder {

    private static final Logger log = LoggerFactory.getLogger(GenericArgumentBuilder.class);

    /**
     * Dubbo 泛化调用中 POJO 参数的类型元数据键。
     */
    private static final String CLASS_KEY = "class";

    /**
     * 用户或模型不得写入的额外保留字段。
     */
    private static final String TYPE_KEY = "@type";

    /**
     * Java 基本类型及包装类型，按原样传递而无需包装为 Map。这些类型在 Dubbo
     * 泛化调用中不需要类型元数据。
     */
    private static final Set<String> SIMPLE_TYPES = Set.of(
            "int", "long", "boolean", "double", "float", "byte", "short", "char",
            "java.lang.String",
            "java.lang.Integer",
            "java.lang.Long",
            "java.lang.Boolean",
            "java.lang.Double",
            "java.lang.Float",
            "java.lang.Byte",
            "java.lang.Short",
            "java.lang.Character",
            "java.lang.Number",
            "java.lang.Object",
            "java.math.BigDecimal",
            "java.math.BigInteger",
            "java.util.Date",
            "java.time.LocalDate",
            "java.time.LocalDateTime",
            "java.time.LocalTime",
            "java.time.Instant",
            "java.time.ZonedDateTime",
            "java.util.Map",
            "java.util.HashMap",
            "java.util.LinkedHashMap",
            "java.util.TreeMap",
            "java.util.List",
            "java.util.ArrayList",
            "java.util.LinkedList",
            "java.util.Set",
            "java.util.HashSet",
            "java.util.LinkedHashSet",
            "java.util.TreeSet",
            "java.util.Collection",
            "java.lang.Enum"
    );

    /**
     * 为 Dubbo 泛化调用构建参数数组。
     *
     * <p>对于位置 {@code i} 处的每个参数：</p>
     * <ul>
     * <li>如果 {@code parameterTypes[i]} 是基本类型/包装类型/String/Map/List
     * 类型：参数按原样传递。</li>
     * <li>如果 {@code parameterTypes[i]} 是 POJO 类型：参数被包装到
     * {@link HashMap} 中，{@code class} 字段设置为 protocolType。用户或模型
     * 输出中已有的任何 {@code class} 或 {@code @type} 键会先被移除。</li>
     * </ul>
     *
     * @param boundArguments 已完全解析、按位置排序的参数
     * @param parameterTypes 接口声明中精确的类型名（字符串）
     * @return 用于 {@code genericService.$invoke} 的参数数组
     * @throws NullPointerException 如果任一参数为 null
     * @throws IllegalArgumentException 如果参数列表与类型列表长度不同
     */
    public Object[] buildArguments(List<Object> boundArguments, List<String> parameterTypes) {
        Objects.requireNonNull(boundArguments, "boundArguments must not be null");
        Objects.requireNonNull(parameterTypes, "parameterTypes must not be null");

        if (boundArguments.size() != parameterTypes.size()) {
            throw new IllegalArgumentException(
                    "Argument count (" + boundArguments.size()
                            + ") does not match parameter type count ("
                            + parameterTypes.size() + ")");
        }

        Object[] result = new Object[boundArguments.size()];
        for (int i = 0; i < boundArguments.size(); i++) {
            Object arg = boundArguments.get(i);
            String protocolType = parameterTypes.get(i);
            result[i] = buildSingleArgument(arg, protocolType);
        }

        log.debug("Built {} generic arguments for Dubbo invocation", result.length);
        return result;
    }

    /**
     * 为给定的值和协议类型构建单个泛化参数。
     *
     * @param arg 已绑定的参数值
     * @param protocolType Java 类型名的全限定字符串
     * @return 泛化调用参数
     */
    @SuppressWarnings("unchecked")
    private Object buildSingleArgument(Object arg, String protocolType) {
        if (arg == null) {
            return null;
        }

        // 简单类型按原样传递
        if (SIMPLE_TYPES.contains(protocolType)) {
            return arg;
        }

        // 数组类型按原样传递（Dubbo 原生支持数组）
        if (protocolType.endsWith("[]")) {
            return arg;
        }

        // POJO 类型：包装为 HashMap，将 "class" 字段设置为 protocolType
        if (arg instanceof Map<?, ?> mapArg) {
            Map<String, Object> genericMap = new LinkedHashMap<>(mapArg.size() + 1);
            for (Map.Entry<?, ?> entry : mapArg.entrySet()) {
                String key = String.valueOf(entry.getKey());
                // 用户和模型不得写入 class、@type 字段
                // 移除任何被注入的字段（纵深防御）
                if (CLASS_KEY.equals(key) || TYPE_KEY.equals(key)) {
                    log.warn("Removing reserved '{}' key from model/user output", key);
                    continue;
                }
                genericMap.put(key, entry.getValue());
            }
            // 适配器从确认的 protocolType 生成类型元数据
            genericMap.put(CLASS_KEY, protocolType);
            return genericMap;
        }

        // 如果参数不是 Map 但类型是 POJO，则无法构建正确的泛化表示。
        // 正常情况下不应发生，因为模型输出是 JSON 兼容的（Map/List/基本类型）。
        log.warn("Argument for POJO type '{}' is not a Map (actual: {}), "
                + "wrapping with class metadata only",
        protocolType, arg.getClass().getName());
        Map<String, Object> genericMap = new HashMap<>();
        genericMap.put(CLASS_KEY, protocolType);
        return genericMap;
    }
}
