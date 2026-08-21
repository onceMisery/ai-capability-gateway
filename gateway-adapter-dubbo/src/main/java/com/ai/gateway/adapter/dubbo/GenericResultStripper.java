package com.ai.gateway.adapter.dubbo;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 递归地从 Dubbo 泛化调用结果中剥离协议元数据键。
 *
 * <p>当 Provider 通过 Dubbo 泛化调用返回 Map 时，会携带诸如 {@code class} 之类的
 * 协议元数据键。适配器必须在构建中立的 JSON 树之前递归剥离这些键，因为它们是
 * 不允许进入 Envelope 判定、投影、Schema 校验或任何外部输出的。</p>
 *
 * <p>剥离必须发生在以下步骤之前：</p>
 * <ul>
 * <li>Envelope 判定</li>
 * <li>投影白名单应用</li>
 * <li>Schema 校验</li>
 * <li>任何外部输出</li>
 * </ul>
 *
 * <p>剥离是递归的：对于 Map 值，移除 {@code class} 键后，其余值会被递归处理。
 * 对于 List 值，每个元素会被递归处理。基本类型的值按原样返回。</p>
 *
 * @author cmiracle@163.com
 * @since 0.1.0
 */
@Slf4j
@Component
public class GenericResultStripper {

    /**
     * Dubbo 在泛化调用 Map 结果中添加的协议元数据键，用于指示原始 Java 类型。
     */
    private static final String CLASS_KEY = "class";

    /**
     * 某些序列化框架可能添加到泛化调用结果中的额外协议元数据键。
     */
    private static final String TYPE_KEY = "@type";

    /**
     * 递归地从结果中剥离协议元数据键。
     *
     * <p>处理规则：</p>
     * <ul>
     * <li>如果结果是 {@link Map}：移除 {@code class} 和 {@code @type} 键，
     * 然后递归处理所有剩余的值。</li>
     * <li>如果结果是 {@link List}：递归处理每个元素。</li>
     * <li>如果结果是其他任何类型：按原样返回。</li>
     * </ul>
     *
     * @param result 原始的 Dubbo 泛化调用结果
     * @return 已移除协议元数据键的剥离结果
     */
    @SuppressWarnings("unchecked")
    public Object strip(Object result) {
        if (result == null) {
            return null;
        }

        if (result instanceof Map<?, ?> rawMap) {
            Map<String, Object> cleaned = new LinkedHashMap<>(rawMap.size());
            for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
                String key = String.valueOf(entry.getKey());
                // 剥离协议元数据键
                if (CLASS_KEY.equals(key) || TYPE_KEY.equals(key)) {
                    continue;
                }
                // 递归剥离嵌套值
                cleaned.put(key, strip(entry.getValue()));
            }
            return cleaned;
        }

        if (result instanceof List<?> rawList) {
            List<Object> cleaned = new ArrayList<>(rawList.size());
            for (Object element : rawList) {
                cleaned.add(strip(element));
            }
            return cleaned;
        }

        if (result.getClass().isArray()) {
            // 处理基本类型数组——按原样返回，它们与 JSON 兼容
            // 对象数组会被递归处理
            if (result instanceof Object[] rawArray) {
                Object[] cleaned = new Object[rawArray.length];
                for (int i = 0; i < rawArray.length; i++) {
                    cleaned[i] = strip(rawArray[i]);
                }
                return cleaned;
            }
            // 基本类型数组（int[]、long[] 等）与 JSON 兼容
            return result;
        }

        // 基本类型、String、Number、Boolean——按原样返回
        return result;
    }
}
