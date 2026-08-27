package com.ai.gateway.adapter.a2a;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

/**
 * 基于 JDK {@link HttpClient} 的 A2A 出站传输实现。
 *
 * <p>{@link HttpClient} 本身是线程安全且内建连接池的，因此这里持有单实例而不是按请求新建：
 * 真正不可共享的是 A2A 会话语义对象（{@code Message} 在 SDK 里带 setter），而它由
 * {@link A2aInvocationAdapter} 每次调用现场构造。把「连接复用」与「会话对象不可共享」分开看，
 * 才能既满足并发约束又不为每次出站调用付一次 TCP/TLS 握手的代价。</p>
 *
 * <p><b>响应体大小上限在这里生效</b>：只有读取字节的这一层能在超限时提前停下。
 * 上限之外的字节被丢弃并以 {@code 413} 上报，让适配器按稳定错误码处理，
 * 而不是把一份超大响应整体读进内存再判定。</p>
 *
 * @author cmiracle@163.com
 * @since 0.1.0
 */
public final class JdkA2aClientTransport implements A2aClientTransport {

    /** JSON-RPC 请求与响应的媒体类型。 */
    private static final String MEDIA_TYPE_JSON = "application/json";

    /** 响应体超限时上报的状态码，与 HTTP 语义一致，便于适配器统一映射。 */
    private static final int STATUS_PAYLOAD_TOO_LARGE = 413;

    private final HttpClient httpClient;
    private final long maxResponseBytes;

    /**
     * @param httpClient       线程安全的 HTTP 客户端，不能为 {@code null}
     * @param maxResponseBytes 响应体字节上限，必须为正数
     */
    public JdkA2aClientTransport(HttpClient httpClient, long maxResponseBytes) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        if (maxResponseBytes <= 0) {
            throw new IllegalArgumentException("maxResponseBytes must be positive");
        }
        this.maxResponseBytes = maxResponseBytes;
    }

    @Override
    public A2aClientResponse send(URI endpoint, String jsonRpcBody, long timeoutMillis)
            throws Exception {
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofMillis(timeoutMillis))
                .header("Content-Type", MEDIA_TYPE_JSON)
                .header("Accept", MEDIA_TYPE_JSON)
                .POST(HttpRequest.BodyPublishers.ofString(jsonRpcBody, StandardCharsets.UTF_8))
                .build();
        HttpResponse<InputStream> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream stream = response.body()) {
            byte[] bytes = stream.readNBytes((int) Math.min(maxResponseBytes + 1, Integer.MAX_VALUE));
            if (bytes.length > maxResponseBytes) {
                return new A2aClientResponse(STATUS_PAYLOAD_TOO_LARGE, null);
            }
            return new A2aClientResponse(response.statusCode(),
                    new String(bytes, StandardCharsets.UTF_8));
        } catch (IOException e) {
            // 读取中断与连接失败在归因上是同一类事实：远端没有给出完整响应。
            return new A2aClientResponse(response.statusCode(), null);
        }
    }
}
