package com.ai.gateway.domain.model;

/**
 * 从 Provider 数据到公开输出的单个 JSON Pointer 投影映射。
 *
 * <p>将 {@code projection} 定义为一组从 Provider 数据载荷到公开输出的 JSON Pointer
 * 白名单映射。未被任何投影条目映射的字段不会离开网关。</p>
 *
 * <p>若未配置投影，则整个提取数据必须精确匹配 {@code publicSchema}。</p>
 *
 * @param from 指向 Provider 数据载荷的 JSON Pointer（如 {@code "/orderNo"}）
 * @param to 指向公开输出的 JSON Pointer（如 {@code "/orderNo"}）
 * @since 0.1.0
 */
public record ProjectionMapping(String from, String to) {

    /**
     * 紧凑构造器，执行 null 检查。
     *
     * @param from 指向 Provider 数据的 JSON Pointer
     * @param to 指向公开输出的 JSON Pointer
     */
    public ProjectionMapping {
        java.util.Objects.requireNonNull(from, "from must not be null");
        java.util.Objects.requireNonNull(to, "to must not be null");
    }
}
