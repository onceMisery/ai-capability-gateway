package com.ai.gateway.domain.model;

/**
 * 决定适配器在投影与脱敏之前如何解析协议响应。
 *
 * <p>定义两种模式：</p>
 * <ul>
 * <li>{@link #ENVELOPE} - 响应为包裹结构，含成功码、data 与可选 message。
 * 信封配置必须声明 {@code codePath}、{@code successValues} 与 {@code dataPath}。</li>
 * <li>{@link #DIRECT} - 协议返回值的根节点即为 data，不声明成功码路径。</li>
 * </ul>
 *
 * @see OutputContract
 * @see EnvelopeConfig
 * @since 0.1.0
 */
public enum OutputMode {
    /**
     * 信封模式。响应在投影前须用配置的 codePath、successValues、dataPath 解包。
     */
    ENVELOPE,

    /**
     * 直接模式。协议返回根节点直接作为 data，不做信封解包，不声明成功码路径。
     */
    DIRECT
}
