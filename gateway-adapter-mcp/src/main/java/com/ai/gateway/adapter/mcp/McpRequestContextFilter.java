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

/** Converts MCP Servlet requests into the domain request context. */
public final class McpRequestContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        McpRequestContextHolder.set(from(request));
        try {
            filterChain.doFilter(request, response);
        } finally {
            McpRequestContextHolder.clear();
        }
    }

    private static RequestContext from(HttpServletRequest request) {
        return new RequestContext(headers(request), cookies(request),
                queryParams(request), request.getRemoteAddr());
    }

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
