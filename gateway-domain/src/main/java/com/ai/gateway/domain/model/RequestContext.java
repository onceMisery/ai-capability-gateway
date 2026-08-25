package com.ai.gateway.domain.model;

import java.util.Map;

/**
 * 入站请求所携带的、与鉴权相关信息的一种抽象。
 *
 * <p>该 record 将 Domain 层与 Servlet API 解耦：web 层调用方从
 * {@code HttpServletRequest}（请求头、Cookie、查询参数、远端地址）填充它，而
 * {@link com.ai.gateway.domain.port.AuthenticationPort} 的实现无需任何框架依赖即可
 * 从中解析调用方身份。</p>
 *
 * <p>客户端自声明的身份请求头（如 {@code userId}、{@code orgId}、{@code tenantId}）
 * 不构成鉴权结果。它们可出于便利在此携带，但 {@link
 * com.ai.gateway.domain.port.AuthenticationPort} 在构造 {@link Principal} 时绝不直接
 * 信任它们。</p>
 *
 * <p>所有映射均被防御性拷贝并设为不可修改。缺失值用空映射表示，而非 {@code null}。</p>
 *
 * @param headers HTTP 请求头（请求头名 → 值）
 * @param cookies HTTP 请求 Cookie（Cookie 名 → 值）
 * @param queryParams 查询参数（参数名 → 值）
 * @param remoteAddr 客户端远端地址（可为 {@code null}）
 * @since 0.1.0
 */
public record RequestContext(
        Map<String, String> headers,
        Map<String, String> cookies,
        Map<String, String> queryParams,
        String remoteAddr
) {

    /**
     * 紧凑构造器，对可变映射执行防御性拷贝。
     *
     * @param headers HTTP 请求头
     * @param cookies HTTP 请求 Cookie
     * @param queryParams 查询参数
     * @param remoteAddr 客户端远端地址
     */
    public RequestContext {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        cookies = cookies == null ? Map.of() : Map.copyOf(cookies);
        queryParams = queryParams == null ? Map.of() : Map.copyOf(queryParams);
    }

    /**
     * 创建一个不含请求头、Cookie 或查询参数的空请求上下文。
     *
     * @return 空的 {@link RequestContext}；永不为 {@code null}
     */
    public static RequestContext empty() {
        return new RequestContext(Map.of(), Map.of(), Map.of(), null);
    }

    /**
     * 使用大小写不敏感匹配按名查找请求头值。
     *
     * @param name 请求头名
     * @return 请求头值，缺失时为 {@code null}
     */
    public String header(String name) {
        if (name == null) {
            return null;
        }
        String direct = headers.get(name);
        if (direct != null) {
            return direct;
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (name.equalsIgnoreCase(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }
}
