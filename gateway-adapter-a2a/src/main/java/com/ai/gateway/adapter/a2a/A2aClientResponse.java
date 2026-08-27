package com.ai.gateway.adapter.a2a;

/**
 * 一次 A2A 出站调用的原始响应。
 *
 * <p>刻意只承载状态码与响应体文本，不承载任何 HTTP 客户端类型：本模块因此不需要依赖具体
 * HTTP 栈，出站适配器也可以在纯单元测试里被完整驱动——包括超时、非 2xx、畸形响应体这些
 * 恰恰最需要被测到的分支。</p>
 *
 * @param statusCode 传输层状态码
 * @param body       响应体文本，允许为 {@code null}
 * @author cmiracle@163.com
 * @since 0.1.0
 */
public record A2aClientResponse(int statusCode, String body) {
}
