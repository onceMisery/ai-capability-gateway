package com.ai.gateway.adapter.rest;

import java.net.URI;

@FunctionalInterface
public interface RestEndpointResolver {

    URI resolve(String endpointRef);
}
