package com.ai.gateway.adapter.web.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Servlet filter that rejects requests exceeding a configurable maximum
 * size.
 *
 * <p>The default maximum request size is 64 KiB (65536 bytes), which is
 * sufficient for natural-language queries and write-operation confirmations.
 * Requests larger than the configured limit receive HTTP 413 Payload Too
 * Large.</p>
 *
 * <p>The filter wraps the request in a size-checking wrapper that counts
 * bytes as they are read. If the content length is declared in the
 * {@code Content-Length} header and exceeds the limit, the request is
 * rejected immediately without reading the body.</p>
 *
 * <p>This filter runs with high priority (before other filters) to ensure
 * that oversized requests are rejected as early as possible in the
 * processing pipeline.</p>
 *
 * @since 0.1.0
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RequestSizeLimitFilter implements jakarta.servlet.Filter {

    private static final Logger log = LoggerFactory.getLogger(RequestSizeLimitFilter.class);

    /**
     * The default maximum request size in bytes (64 KiB).
     */
    public static final int DEFAULT_MAX_REQUEST_SIZE = 64 * 1024;

    private final int maxRequestSize;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Constructs a new RequestSizeLimitFilter.
     *
     * @param maxRequestSize the maximum allowed request size in bytes;
     * defaults to {@value #DEFAULT_MAX_REQUEST_SIZE}
     * if not configured or non-positive
     */
    public RequestSizeLimitFilter(
            @Value("${gateway.max-request-size-bytes:${gateway.web.max-request-size:65536}}")
            int maxRequestSize) {
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

        // Fast path: check Content-Length header if present
        int contentLength = httpRequest.getContentLength();
        if (contentLength > maxRequestSize) {
            rejectRequest(httpResponse, contentLength);
            return;
        }

        // Wrap the request to monitor actual bytes read
        SizeLimitedHttpServletRequestWrapper wrappedRequest =
                new SizeLimitedHttpServletRequestWrapper(httpRequest, maxRequestSize);

        try {
            chain.doFilter(wrappedRequest, response);
        } catch (RequestTooLargeException e) {
            rejectRequest(httpResponse, e.getActualSize());
        }
    }

    /**
     * Rejects the request with HTTP 413 Payload Too Large.
     *
     * @param response the HTTP response
     * @param actualSize the actual or declared request size
     * @throws IOException if writing the response fails
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
     * Exception thrown when the actual request body exceeds the maximum size.
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
     * Wrapper for HttpServletRequest that monitors the number of bytes read
     * from the input stream and throws {@link RequestTooLargeException} if
     * the limit is exceeded.
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
     * ServletInputStream wrapper that counts bytes read and throws
     * {@link RequestTooLargeException} when the limit is exceeded.
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
