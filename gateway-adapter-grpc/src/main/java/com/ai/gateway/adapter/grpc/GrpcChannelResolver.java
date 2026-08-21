package com.ai.gateway.adapter.grpc;

import io.grpc.ManagedChannel;

@FunctionalInterface
public interface GrpcChannelResolver {

    ManagedChannel resolve(String endpointRef);
}
