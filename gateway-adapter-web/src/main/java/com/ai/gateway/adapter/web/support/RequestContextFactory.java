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
 * Builds a framework-agnostic {@link RequestContext} from a Servlet
 * {@link HttpServletRequest}.
 *
 * <p>This is the single place where the web layer adapts the Servlet API to
 * the Domain layer's request abstraction, keeping {@code gateway-domain}
 * free of any {@code jakarta.servlet} dependency. The produced context
 * carries headers, cookies, query parameters, and the client remote address
 * — exactly the inputs an {@code AuthenticationPort} implementation needs to
 * resolve the caller identity.</p>
 *
 * @since 0.1.0
 */
@Component
public class RequestContextFactory {

    /**
     * Creates a {@link RequestContext} from the given servlet request.
     *
     * @param request the inbound HTTP request; never {@code null}
     * @return the populated request context; never {@code null}
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
