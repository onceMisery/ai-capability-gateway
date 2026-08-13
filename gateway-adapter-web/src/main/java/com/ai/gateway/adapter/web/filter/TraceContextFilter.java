package com.ai.gateway.adapter.web.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.beans.factory.annotation.Autowired;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Servlet filter for distributed trace context management.
 *
 * <p>This filter is responsible for:</p>
 * <ol>
 * <li><strong>Trace ID generation</strong> — generates a server-side trace
 * ID for each request. The trace ID is propagated through MDC for
 * structured logging and stored as a request attribute for downstream
 * pipeline stages.</li>
 * <li><strong>Client Request ID correlation</strong> — extracts the
 * client-provided request ID (if any) and uses it solely as a
 * correlation field. The client request ID does not become the
 * trace ID; it is logged alongside the trace ID for debugging and
 * cross-referencing.</li>
 * <li><strong>Span name definition</strong> — defines the 9 canonical
 * trace spans for the natural-language routing pipeline.
 * These span names are stored as request attributes so that downstream
 * instrumentation can create spans with consistent names.</li>
 * </ol>
 *
 * <p>The 9 canonical spans:</p>
 * <ol>
 * <li>{@code authenticate} — authentication and Principal construction
 *</li>
 * <li>{@code authorize.visibility} — visibility authorization pass 1
 *</li>
 * <li>{@code catalog.retrieve} — BM25 Top-K candidate retrieval
 *</li>
 * <li>{@code llm.route} — LLM capability selection and parameter
 * extraction</li>
 * <li>{@code arguments.validate} — argument binding and schema validation
 *</li>
 * <li>{@code authorize.execute} — execution authorization pass 2
 *</li>
 * <li>{@code adapter.invoke} — protocol adapter invocation</li>
 * <li>{@code result.normalize} — result normalization, projection, and
 * redaction</li>
 * <li>{@code audit.persist} — terminal audit event persistence
 *</li>
 * </ol>
 *
 * <p>Trace attributes record only capability ID, version, snapshot, stable
 * error codes, and durations — never sensitive parameters.</p>
 *
 * @since 0.1.0
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceContextFilter implements jakarta.servlet.Filter {

    private static final Logger log = LoggerFactory.getLogger(TraceContextFilter.class);

    // MDC keys
    public static final String MDC_TRACE_ID = "traceId";
    public static final String MDC_CLIENT_REQUEST_ID = "clientRequestId";
    public static final String MDC_SPAN_ID = "spanId";

    // Request attribute keys
    public static final String ATTR_TRACE_ID = "gateway.traceId";
    public static final String ATTR_CLIENT_REQUEST_ID = "gateway.clientRequestId";
    public static final String ATTR_SPAN_NAMES = "gateway.spanNames";

    // HTTP headers
    private static final String HEADER_CLIENT_REQUEST_ID = "X-Request-Id";
    private static final String HEADER_TRACE_ID = "X-Trace-Id";

    /**
     * The 9 canonical trace spans for the natural-language routing pipeline
     */
    public static final List<String> PIPELINE_SPANS = List.of(
            "authenticate",
            "authorize.visibility",
            "catalog.retrieve",
            "llm.route",
            "arguments.validate",
            "authorize.execute",
            "adapter.invoke",
            "result.normalize",
            "audit.persist"
    );

    private final ObservationRegistry observationRegistry;

    public TraceContextFilter() {
        this(ObservationRegistry.NOOP);
    }

    @Autowired
    public TraceContextFilter(ObservationRegistry observationRegistry) {
        this.observationRegistry = observationRegistry == null
                ? ObservationRegistry.NOOP : observationRegistry;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response,
                         FilterChain chain) throws IOException, ServletException {

        if (!(request instanceof HttpServletRequest httpRequest) ||
                !(response instanceof HttpServletResponse httpResponse)) {
            chain.doFilter(request, response);
            return;
        }

        // Generate server-side Trace ID
        String traceId = httpRequest.getHeader(HEADER_TRACE_ID);
        if (!isValidTraceId(traceId)) {
            traceId = generateTraceId();
        } else {
            traceId = traceId.toLowerCase(java.util.Locale.ROOT);
        }

        // Extract Client Request ID for correlation only
        // The client request ID is NOT used as the trace ID.
        String clientRequestId = httpRequest.getHeader(HEADER_CLIENT_REQUEST_ID);
        if (!isValidRequestId(clientRequestId)) {
            clientRequestId = UUID.randomUUID().toString();
        }

        // Set up MDC for structured logging
        MDC.put(MDC_TRACE_ID, traceId);
        MDC.put(MDC_CLIENT_REQUEST_ID, clientRequestId);
        MDC.put(MDC_SPAN_ID, "entry");

        // Store trace context in request attributes for downstream access
        httpRequest.setAttribute(ATTR_TRACE_ID, traceId);
        httpRequest.setAttribute(ATTR_CLIENT_REQUEST_ID, clientRequestId);
        httpRequest.setAttribute(ATTR_SPAN_NAMES, PIPELINE_SPANS);

        // Set response headers for trace correlation
        httpResponse.setHeader(HEADER_TRACE_ID, traceId);
        httpResponse.setHeader(HEADER_CLIENT_REQUEST_ID, clientRequestId);

        log.debug("Trace context established: traceId={}, clientRequestId={}",
                traceId, clientRequestId);

        Observation observation = Observation.start("http.server", observationRegistry)
                .lowCardinalityKeyValue("method", httpRequest.getMethod())
                .lowCardinalityKeyValue("route", normalizeRoute(httpRequest.getRequestURI()));
        try (Observation.Scope ignored = observation.openScope()) {
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException | Error e) {
            observation.error(e);
            throw e;
        } finally {
            observation.stop();
            // Clean up MDC to prevent thread-local leakage
            MDC.remove(MDC_TRACE_ID);
            MDC.remove(MDC_CLIENT_REQUEST_ID);
            MDC.remove(MDC_SPAN_ID);
        }
    }

    private boolean isValidTraceId(String traceId) {
        return traceId != null && traceId.matches("(?i)[0-9a-f]{32}")
                && !traceId.chars().allMatch(c -> c == '0');
    }

    private boolean isValidRequestId(String requestId) {
        return requestId != null && requestId.matches("[A-Za-z0-9._:-]{1,128}");
    }

    private String normalizeRoute(String uri) {
        if (uri == null || uri.isBlank()) return "unknown";
        if (uri.startsWith("/api/v1")) return "/api/v1";
        if (uri.startsWith("/admin/v1")) return "/admin/v1";
        return "/other";
    }

    /**
     * Generates a server-side trace ID.
     *
     * <p>The trace ID is a 32-character hexadecimal string (128 bits),
     * compatible with W3C Trace Context and OpenTelemetry.</p>
     *
     * @return the generated trace ID
     */
    private String generateTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * Returns the 9 canonical pipeline span names.
     *
     * @return an unmodifiable list of span names
     */
    public static List<String> getPipelineSpans() {
        return PIPELINE_SPANS;
    }
}
