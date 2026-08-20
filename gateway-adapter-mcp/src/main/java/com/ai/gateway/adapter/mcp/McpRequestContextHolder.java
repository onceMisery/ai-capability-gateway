package com.ai.gateway.adapter.mcp;

import com.ai.gateway.domain.model.RequestContext;
import com.ai.gateway.domain.model.Principal;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

/** Binds the authenticated Servlet request to the synchronous MCP handler. */
public final class McpRequestContextHolder {

    private static final ThreadLocal<RequestContext> CURRENT = new ThreadLocal<>();
    private static final Object REACTOR_REQUEST_CONTEXT = new Object();
    private static final Object REACTOR_PRINCIPAL = new Object();

    private McpRequestContextHolder() {
    }

    public static void set(RequestContext context) {
        CURRENT.set(context);
    }

    public static RequestContext current() {
        RequestContext context = CURRENT.get();
        return context == null ? RequestContext.empty() : context;
    }

    public static void clear() {
        CURRENT.remove();
    }

    static Context bindAuthenticated(Context context,
                                     RequestContext requestContext,
                                     Principal principal) {
        return context.put(REACTOR_REQUEST_CONTEXT, requestContext)
                .put(REACTOR_PRINCIPAL, principal);
    }

    static RequestContext current(ContextView context) {
        return context.getOrDefault(REACTOR_REQUEST_CONTEXT, RequestContext.empty());
    }

    static Principal principal(ContextView context) {
        return context.getOrDefault(REACTOR_PRINCIPAL, null);
    }
}
