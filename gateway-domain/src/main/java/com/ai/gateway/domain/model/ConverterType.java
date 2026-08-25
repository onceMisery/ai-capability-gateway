package com.ai.gateway.domain.model;

/**
 * 参数绑定的受控类型转换器封闭白名单。
 *
 * <p>定义一组封闭、确定性、单值的类型转换器枚举。它们用于解决原始值类型
 *（来自模型输出、Principal 声明或常量）与协议参数类型（如日期字符串转 epoch 毫秒 Long、
 * 或枚举大小写规范化）之间的常见不匹配。</p>
 *
 * <p>关键约束：</p>
 * <ul>
 * <li>转换器仅由确定性 Java 代码实现——不允许 SpEL、脚本引擎或任意表达式。</li>
 * <li>每个转换器作用于单一源值到单一目标字段，不支持跨字段组合。</li>
 * <li>转换失败（格式不匹配、溢出、非法枚举值）一律视为
 * {@code ARGUMENT_VALIDATION_FAILED}，不会静默回退到原值或 null。</li>
 * <li>契约校验器必须在导入时验证转换器名属于已注册白名单。</li>
 * <li>新增或修改转换器须遵循平台发布流程，不通过清单动态扩展。</li>
 * </ul>
 *
 * @see ArgumentBinding
 * @see FieldBinding
 * @since 0.1.0
 */
public enum ConverterType {
    /**
     * 将 ISO-8601 日期/时间字符串转换为 epoch 毫秒（Long）。
     * 例如 {@code "2026-07-21T10:00:00Z"} 变为 {@code 1753092000000L}。
     */
    ISO_DATE_TO_EPOCH_MILLIS,

    /**
     * 将类枚举字符串规范化为大写。例如 {@code "pending"} 变为 {@code "PENDING"}。
     */
    ENUM_UPPERCASE,

    /**
     * 去除字符串值首尾空白。例如 {@code " order123 "} 变为 {@code "order123"}。
     */
    STRING_TRIM
}
