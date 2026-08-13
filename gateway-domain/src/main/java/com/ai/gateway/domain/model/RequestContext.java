package com.ai.gateway.domain.model;

import java.util.Map;

/**
 * Abstraction of the authentication-relevant information carried by an
 * inbound request.
 *
 * <p>This record decouples the Domain layer from the Servlet API: callers
 * in the web layer populate it from {@code HttpServletRequest} (headers,
 * cookies, query parameters, remote address), and {@link
 * com.ai.gateway.domain.port.AuthenticationPort} implementations resolve
 * the caller identity from it without any framework dependency.</p>
 *
 * <p>Identity headers self-declared by the client (e.g., {@code userId},
 * {@code orgId}, {@code tenantId}) do not constitute authentication
 * results. They may be carried here for convenience, but the {@link
 * com.ai.gateway.domain.port.AuthenticationPort} must never trust them
 * directly when constructing the {@link Principal}.</p>
 *
 * <p>All maps are defensively copied and made unmodifiable. A missing
 * value is represented by an empty map, never {@code null}.</p>
 *
 * @param headers the HTTP request headers (header name → value)
 * @param cookies the HTTP request cookies (cookie name → value)
 * @param queryParams the query parameters (parameter name → value)
 * @param remoteAddr the client remote address (may be {@code null})
 * @since 0.1.0
 */
public record RequestContext(
        Map<String, String> headers,
        Map<String, String> cookies,
        Map<String, String> queryParams,
        String remoteAddr
) {

    /**
     * Compact constructor performing defensive copying of mutable maps.
     *
     * @param headers the HTTP request headers
     * @param cookies the HTTP request cookies
     * @param queryParams the query parameters
     * @param remoteAddr the client remote address
     */
    public RequestContext {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        cookies = cookies == null ? Map.of() : Map.copyOf(cookies);
        queryParams = queryParams == null ? Map.of() : Map.copyOf(queryParams);
    }

    /**
     * Creates an empty request context with no headers, cookies, or query
     * parameters.
     *
     * @return an empty {@link RequestContext}; never {@code null}
     */
    public static RequestContext empty() {
        return new RequestContext(Map.of(), Map.of(), Map.of(), null);
    }

    /**
     * Looks up a header value by name using case-insensitive matching.
     *
     * @param name the header name
     * @return the header value, or {@code null} if absent
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
