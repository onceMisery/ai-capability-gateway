package com.ai.gateway.adapter.rest;

import java.net.http.HttpRequest;

@FunctionalInterface
public interface RestHttpClient {

    RestHttpResponse send(HttpRequest request, long timeoutMs) throws Exception;
}
