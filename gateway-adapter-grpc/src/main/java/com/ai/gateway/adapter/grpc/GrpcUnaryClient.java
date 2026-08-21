package com.ai.gateway.adapter.grpc;

import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Descriptors;

@FunctionalInterface
public interface GrpcUnaryClient {

    DynamicMessage unaryCall(String endpointRef,
                              Descriptors.MethodDescriptor method,
                              DynamicMessage request,
                              long deadlineMs) throws Exception;
}
