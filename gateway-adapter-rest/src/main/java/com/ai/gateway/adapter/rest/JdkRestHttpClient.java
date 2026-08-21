package com.ai.gateway.adapter.rest;

import lombok.RequiredArgsConstructor;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@RequiredArgsConstructor
public class JdkRestHttpClient implements RestHttpClient {

    private final HttpClient client;

    public JdkRestHttpClient() {
        this(HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    @Override
    public RestHttpResponse send(HttpRequest request, long timeoutMs) throws Exception {
        HttpRequest timedRequest = HttpRequest.newBuilder(request.uri())
                .method(request.method(), request.bodyPublisher().orElse(
                        HttpRequest.BodyPublishers.noBody()))
                .headers(request.headers().map().entrySet().stream()
                        .flatMap(entry -> entry.getValue().stream()
                                .flatMap(value -> java.util.stream.Stream.of(
                                        entry.getKey(), value)))
                        .toArray(String[]::new))
                .timeout(Duration.ofMillis(timeoutMs))
                .build();
        HttpResponse<String> response = client.send(
                timedRequest, HttpResponse.BodyHandlers.ofString());
        return new RestHttpResponse(response.statusCode(), response.body(),
                response.headers().firstValue("content-type")
                        .map(value -> java.util.Map.of("content-type", value))
                        .orElse(java.util.Map.of()));
    }
}
