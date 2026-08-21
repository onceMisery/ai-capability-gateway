package com.ai.gateway.adapter.grpc;

import com.google.protobuf.Descriptors;

@FunctionalInterface
public interface GrpcMethodRegistry {

    Descriptors.MethodDescriptor resolve(String registryRef, String serviceName, String methodName);
}
