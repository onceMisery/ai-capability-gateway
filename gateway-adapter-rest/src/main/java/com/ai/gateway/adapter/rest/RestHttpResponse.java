package com.ai.gateway.adapter.rest;

import java.util.Map;

public record RestHttpResponse(int statusCode, String body, Map<String, String> headers) {

    public RestHttpResponse {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }
}
