package com.ai.gateway.adapter.web.support;

import com.ai.gateway.domain.model.RequestContext;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 从 Servlet {@link HttpServletRequest} 构建与框架无关的 {@link RequestContext}。
 *
 * <p>这是 Web 层将 Servlet API 适配到 Domain 层请求抽象的唯一位置，使
 * {@code gateway-domain} 不依赖任何 {@code jakarta.servlet}。构建出的上下文
 * 携带请求头、Cookie、查询参数以及客户端远程地址——恰好是一个
 * {@code AuthenticationPort} 实现解析调用方身份所需的全部输入。</p>
 *
 * @author cmiracle@163.com
 * @since 0.1.0
 */
@Component
public class RequestContextFactory {

    /**
     * 从给定的 Servlet 请求构建 {@link RequestContext}。
     *
     * @param request 入站 HTTP 请求；不得为 {@code null}
     * @return 填充完成的请求上下文；不得为 {@code null}
     */
    public RequestContext from(HttpServletRequest request) {
        return new RequestContext(
                extractHeaders(request),
                extractCookies(request),
                extractQueryParams(request),
                request.getRemoteAddr());
    }

    private Map<String, String> extractHeaders(HttpServletRequest request) {
        Map<String, String> headers = new LinkedHashMap<>();
        Enumeration<String> names = request.getHeaderNames();
        if (names != null) {
            while (names.hasMoreElements()) {
                String name = names.nextElement();
                headers.put(name, request.getHeader(name));
            }
        }
        return Collections.unmodifiableMap(headers);
    }

    private Map<String, String> extractCookies(HttpServletRequest request) {
        Map<String, String> cookies = new LinkedHashMap<>();
        Cookie[] requestCookies = request.getCookies();
        if (requestCookies != null) {
            for (Cookie cookie : requestCookies) {
                cookies.put(cookie.getName(), cookie.getValue());
            }
        }
        return Collections.unmodifiableMap(cookies);
    }

    private Map<String, String> extractQueryParams(HttpServletRequest request) {
        Map<String, String> params = new LinkedHashMap<>();
        Map<String, String[]> parameterMap = request.getParameterMap();
        if (parameterMap != null) {
            for (Map.Entry<String, String[]> entry : parameterMap.entrySet()) {
                String[] values = entry.getValue();
                if (values != null && values.length > 0) {
                    params.put(entry.getKey(), values[0]);
                }
            }
        }
        return Collections.unmodifiableMap(params);
    }
}
