package com.ai.gateway.adapter.mcp;

import com.ai.gateway.domain.model.RequestContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 将 MCP 的 Servlet 请求转换为领域层的请求上下文（RequestContext）。
 *
 * <p>每次请求在过滤器内建立请求上下文，请求处理完成后清除，避免线程复用导致的上下文泄漏。</p>
 *
 * @author cmiracle@163.com
 * @since 0.1.0
 */
public final class McpRequestContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        // 在当前线程建立请求上下文
        McpRequestContextHolder.set(from(request));
        try {
            filterChain.doFilter(request, response);
        } finally {
            // 请求结束后清除，防止线程池复用串扰
            McpRequestContextHolder.clear();
        }
    }

    /**
     * 从 Servlet 请求中提取头、Cookie、查询参数与客户端地址，构造领域请求上下文。
     */
    private static RequestContext from(HttpServletRequest request) {
        return new RequestContext(headers(request), cookies(request),
                queryParams(request), request.getRemoteAddr());
    }

    /**
     * 提取全部请求头，返回只读的有序映射。
     */
    private static Map<String, String> headers(HttpServletRequest request) {
        Map<String, String> result = new LinkedHashMap<>();
        Enumeration<String> names = request.getHeaderNames();
        if (names != null) {
            while (names.hasMoreElements()) {
                String name = names.nextElement();
                result.put(name, request.getHeader(name));
            }
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * 提取全部 Cookie，返回只读的有序映射。
     */
    private static Map<String, String> cookies(HttpServletRequest request) {
        Map<String, String> result = new LinkedHashMap<>();
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                result.put(cookie.getName(), cookie.getValue());
            }
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * 提取查询参数（每个参数仅取首个值），返回只读的有序映射。
     */
    private static Map<String, String> queryParams(HttpServletRequest request) {
        Map<String, String> result = new LinkedHashMap<>();
        request.getParameterMap().forEach((name, values) -> {
            if (values != null && values.length > 0) {
                result.put(name, values[0]);
            }
        });
        return Collections.unmodifiableMap(result);
    }
}
