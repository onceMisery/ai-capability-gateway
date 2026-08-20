package com.ai.gateway.adapter.web.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ai.gateway.adapter.web.GatewayWebProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

import lombok.extern.slf4j.Slf4j;

/**
 * 拒绝超过可配置最大体积的请求的 Servlet 过滤器。
 *
 * <p>默认最大请求体积为 64 KiB（65536 字节），足以容纳自然语言查询与写操作确认。
 * 超过配置上限的请求将收到 HTTP 413 Payload Too Large。</p>
 *
 * <p>该过滤器将请求包装为体积检测包装器，在读取时逐字节计数。若
 * {@code Content-Length} 头中声明的体积超过上限，则无需读取请求体即可立即拒绝。</p>
 *
 * <p>该过滤器以高优先级运行（位于其他过滤器之前），以确保超体积请求在
 * 处理管道中尽早被拒绝。</p>
 *
 * @author cmiracle@163.com
 * @since 0.1.0
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
@Slf4j
public class RequestSizeLimitFilter implements jakarta.servlet.Filter {

    /**
     * 默认最大请求体积（单位：字节，即 64 KiB）。
     */
    public static final int DEFAULT_MAX_REQUEST_SIZE = 64 * 1024;

    private final int maxRequestSize;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 构造新的 RequestSizeLimitFilter。
     *
     * @param maxRequestSize 允许的最大请求体积（单位：字节）；
     * 若未配置或为非正数，则回退为 {@value #DEFAULT_MAX_REQUEST_SIZE}
     */
    @Autowired
    public RequestSizeLimitFilter(GatewayWebProperties properties) {
        this(properties.getMaxRequestSizeBytes());
    }

    RequestSizeLimitFilter(int maxRequestSize) {
        this.maxRequestSize = maxRequestSize > 0 ? maxRequestSize : DEFAULT_MAX_REQUEST_SIZE;
        log.info("RequestSizeLimitFilter initialized: maxRequestSize={} bytes ({} KiB)",
                this.maxRequestSize, this.maxRequestSize / 1024);
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response,
                         FilterChain chain) throws IOException, ServletException {

        if (!(request instanceof HttpServletRequest httpRequest) ||
                !(response instanceof HttpServletResponse httpResponse)) {
            chain.doFilter(request, response);
            return;
        }

        // 快速路径：若提供了 Content-Length 头则直接校验
        int contentLength = httpRequest.getContentLength();
        if (contentLength > maxRequestSize) {
            rejectRequest(httpResponse, contentLength);
            return;
        }

        // 包装请求以监控实际读取的字节数
        SizeLimitedHttpServletRequestWrapper wrappedRequest =
                new SizeLimitedHttpServletRequestWrapper(httpRequest, maxRequestSize);

        try {
            chain.doFilter(wrappedRequest, response);
        } catch (RequestTooLargeException e) {
            rejectRequest(httpResponse, e.getActualSize());
        }
    }

    /**
     * 以 HTTP 413 Payload Too Large 拒绝请求。
     *
     * @param response HTTP 响应
     * @param actualSize 实际或声明声明的请求体积
     * @throws IOException 写入响应失败时抛出
     */
    private void rejectRequest(HttpServletResponse response, int actualSize)
            throws IOException {
        log.warn("Request rejected: size {} bytes exceeds maximum {} bytes",
                actualSize, maxRequestSize);
        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        response.setContentType("application/json");
        objectMapper.writeValue(response.getWriter(), java.util.Map.of(
                "status", "ERROR",
                "error", java.util.Map.of(
                        "errorCode", "RESULT_TOO_LARGE",
                        "message", "Request payload exceeds maximum allowed size")));
    }

    /**
     * 当实际请求体超过最大体积时抛出的异常。
     */
    static class RequestTooLargeException extends RuntimeException {

        private final int actualSize;

        RequestTooLargeException(int actualSize) {
            super("Request body exceeded maximum size: " + actualSize + " bytes");
            this.actualSize = actualSize;
        }

        int getActualSize() {
            return actualSize;
        }
    }

    /**
     * HttpServletRequest 的包装器，监控从输入流读取的字节数，
     * 一旦超过上限即抛出 {@link RequestTooLargeException}。
     */
    static class SizeLimitedHttpServletRequestWrapper
            extends jakarta.servlet.http.HttpServletRequestWrapper {

        private final int maxSize;
        private jakarta.servlet.ServletInputStream wrappedInputStream;

        SizeLimitedHttpServletRequestWrapper(HttpServletRequest request, int maxSize) {
            super(request);
            this.maxSize = maxSize;
        }

        @Override
        public jakarta.servlet.ServletInputStream getInputStream() throws IOException {
            if (wrappedInputStream == null) {
                wrappedInputStream = new SizeLimitedServletInputStream(
                        super.getInputStream(), maxSize);
            }
            return wrappedInputStream;
        }

        @Override
        public java.io.BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            java.nio.charset.Charset charset = encoding != null
                    ? java.nio.charset.Charset.forName(encoding)
                    : java.nio.charset.StandardCharsets.UTF_8;
            return new java.io.BufferedReader(new java.io.InputStreamReader(
                    getInputStream(), charset));
        }
    }

    /**
     * ServletInputStream 的包装器，统计已读取字节数，
     * 超限时抛出 {@link RequestTooLargeException}。
     */
    static class SizeLimitedServletInputStream extends jakarta.servlet.ServletInputStream {

        private final jakarta.servlet.ServletInputStream delegate;
        private final int maxSize;
        private int bytesRead = 0;
        private boolean finished = false;

        SizeLimitedServletInputStream(jakarta.servlet.ServletInputStream delegate, int maxSize) {
            this.delegate = delegate;
            this.maxSize = maxSize;
        }

        @Override
        public int read() throws IOException {
            if (bytesRead >= maxSize) {
                int extra = delegate.read();
                if (extra != -1) {
                    throw new RequestTooLargeException(bytesRead + 1);
                }
                finished = true;
                return -1;
            }
            int b = delegate.read();
            if (b != -1) {
                bytesRead++;
            } else {
                finished = true;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) {
                return 0;
            }
            if (bytesRead >= maxSize) {
                int extra = delegate.read();
                if (extra != -1) {
                    throw new RequestTooLargeException(bytesRead + 1);
                }
                finished = true;
                return -1;
            }
            int remaining = maxSize - bytesRead;
            int toRead = Math.min(len, remaining);
            int actual = delegate.read(b, off, toRead);
            if (actual > 0) {
                bytesRead += actual;
            } else if (actual == -1) {
                finished = true;
            }
            return actual;
        }

        @Override
        public boolean isFinished() {
            return finished || delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(jakarta.servlet.ReadListener readListener) {
            delegate.setReadListener(readListener);
        }
    }
}
