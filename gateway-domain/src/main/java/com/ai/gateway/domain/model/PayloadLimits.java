package com.ai.gateway.domain.model;

/**
 * 网关处理 JSON 数据树时使用的统一资源预算。
 *
 * <p>该模型只包含 JDK 类型，不依赖 Spring、Jackson 或其他运行时框架。
 * 输入和输出使用不同的字节上限，其余结构限制保持一致。</p>
 *
 * @param maxInputBytes 模型输入树的最大 UTF-8 JSON 字节数
 * @param maxOutputBytes Provider 输出树的全局最大 UTF-8 JSON 字节数
 * @param maxDepth JSON 树最大深度，根节点深度为 0
 * @param maxCollectionLength 单个数组或对象允许的最大成员数
 * @param maxObjectFields 单个对象允许的最大字段数
 * @param maxStringBytes 单个字符串允许的最大 UTF-8 字节数
 * @param maxNodes 单棵树允许的最大节点数
 */
public record PayloadLimits(
        long maxInputBytes,
        long maxOutputBytes,
        int maxDepth,
        int maxCollectionLength,
        int maxObjectFields,
        int maxStringBytes,
        long maxNodes
) {

    /**
     * 使用当前项目原有请求/响应上限和保守结构预算构造默认值。
     *
     * @return 默认预算
     */
    public static PayloadLimits defaults() {
        return new PayloadLimits(
                64 * 1024L,
                1024 * 1024L,
                16,
                1_000,
                1_000,
                16 * 1024,
                10_000L);
    }

    /** 紧凑构造器校验所有预算必须为正数。 */
    public PayloadLimits {
        if (maxInputBytes <= 0 || maxOutputBytes <= 0) {
            throw new IllegalArgumentException("Payload byte limits must be positive");
        }
        if (maxDepth <= 0 || maxCollectionLength <= 0 || maxObjectFields <= 0
                || maxStringBytes <= 0 || maxNodes <= 0) {
            throw new IllegalArgumentException("Payload structural limits must be positive");
        }
    }
}
