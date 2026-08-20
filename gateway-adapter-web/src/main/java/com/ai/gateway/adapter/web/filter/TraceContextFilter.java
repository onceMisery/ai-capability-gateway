package com.ai.gateway.adapter.web.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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

import lombok.extern.slf4j.Slf4j;

/**
 * 用于分布式追踪上下文管理的 Servlet 过滤器。
 *
 * <p>该过滤器负责：</p>
 * <ol>
 * <li><strong>Trace ID 生成</strong> — 为每个请求生成服务端 Trace ID。
 * Trace ID 通过 MDC 向下游传递以支持结构化日志，并作为请求属性供后续
 * 处理阶段使用。</li>
 * <li><strong>Client Request ID 关联</strong> — 提取客户端提供的请求 ID（如有），
 * 仅作为关联字段使用。客户端请求 ID 不会被当作 Trace ID，仅与 Trace ID
 * 一并记录以便调试与交叉引用。</li>
 * <li><strong>Span 名称定义</strong> — 定义自然语言路由管道的 9 个规范追踪
 * Span。这些 Span 名称作为请求属性保存，供下游埋点创建同名的 Span。</li>
 * </ol>
 *
 * <p>9 个规范 Span：</p>
 * <ol>
 * <li>{@code authenticate} — 身份认证与 Principal 构建</li>
 * <li>{@code authorize.visibility} — 可见性鉴权第一道校验</li>
 * <li>{@code catalog.retrieve} — BM25 Top-K 候选检索</li>
 * <li>{@code llm.route} — LLM 能力选择与参数提取</li>
 * <li>{@code arguments.validate} — 参数绑定与 Schema 校验</li>
 * <li>{@code authorize.execute} — 执行鉴权第二道校验</li>
 * <li>{@code adapter.invoke} — 协议适配器调用</li>
 * <li>{@code result.normalize} — 结果归一化、投影与脱敏</li>
 * <li>{@code audit.persist} — 终态审计事件持久化</li>
 * </ol>
 *
 * <p>追踪属性仅记录能力 ID、版本、快照、稳定错误码与耗时——绝不记录敏感参数。</p>
 *
 * @author cmiracle@163.com
 * @since 0.1.0
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class TraceContextFilter implements jakarta.servlet.Filter {

    // MDC 键
    public static final String MDC_TRACE_ID = "traceId";
    public static final String MDC_CLIENT_REQUEST_ID = "clientRequestId";
    public static final String MDC_SPAN_ID = "spanId";

    // 请求属性键
    public static final String ATTR_TRACE_ID = "gateway.traceId";
    public static final String ATTR_CLIENT_REQUEST_ID = "gateway.clientRequestId";
    public static final String ATTR_SPAN_NAMES = "gateway.spanNames";

    // HTTP 头
    private static final String HEADER_CLIENT_REQUEST_ID = "X-Request-Id";
    private static final String HEADER_TRACE_ID = "X-Trace-Id";

    /**
     * 自然语言路由管道的 9 个规范追踪 Span 名称
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

        // 生成服务端 Trace ID
        String traceId = httpRequest.getHeader(HEADER_TRACE_ID);
        if (!isValidTraceId(traceId)) {
            traceId = generateTraceId();
        } else {
            traceId = traceId.toLowerCase(java.util.Locale.ROOT);
        }

        // 仅出于关联目的提取 Client Request ID，该 ID 不会被当作 Trace ID 使用
        String clientRequestId = httpRequest.getHeader(HEADER_CLIENT_REQUEST_ID);
        if (!isValidRequestId(clientRequestId)) {
            clientRequestId = UUID.randomUUID().toString();
        }

        // 配置 MDC 以支持结构化日志
        MDC.put(MDC_TRACE_ID, traceId);
        MDC.put(MDC_CLIENT_REQUEST_ID, clientRequestId);
        MDC.put(MDC_SPAN_ID, "entry");

        // 将追踪上下文存入请求属性，供下游访问
        httpRequest.setAttribute(ATTR_TRACE_ID, traceId);
        httpRequest.setAttribute(ATTR_CLIENT_REQUEST_ID, clientRequestId);
        httpRequest.setAttribute(ATTR_SPAN_NAMES, PIPELINE_SPANS);

        // 设置响应头以支持追踪关联
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
            // 清理 MDC，避免 ThreadLocal 泄漏
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
     * 生成服务端 Trace ID。
     *
     * <p>Trace ID 为 32 位十六进制字符串（128 位），兼容 W3C Trace Context
     * 与 OpenTelemetry。</p>
     *
     * @return 生成的 Trace ID
     */
    private String generateTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 返回 9 个规范管道 Span 名称。
     *
     * @return 不可修改的 Span 名称列表
     */
    public static List<String> getPipelineSpans() {
        return PIPELINE_SPANS;
    }
}
