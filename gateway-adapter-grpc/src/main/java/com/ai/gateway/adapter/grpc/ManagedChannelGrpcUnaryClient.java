package com.ai.gateway.adapter.grpc;

import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Descriptors;
import io.grpc.CallOptions;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.ClientCalls;
import lombok.RequiredArgsConstructor;

import java.util.concurrent.TimeUnit;

@RequiredArgsConstructor
public class ManagedChannelGrpcUnaryClient implements GrpcUnaryClient {

    private final GrpcChannelResolver channelResolver;

    @Override
    public DynamicMessage unaryCall(String endpointRef, Descriptors.MethodDescriptor method,
                                    DynamicMessage request, long deadlineMs) {
        ManagedChannel channel = channelResolver.resolve(endpointRef);
        DynamicMessage defaultResponse = DynamicMessage.getDefaultInstance(method.getOutputType());
        MethodDescriptor<DynamicMessage, DynamicMessage> grpcMethod =
                MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                        .setType(MethodDescriptor.MethodType.UNARY)
                        .setFullMethodName(MethodDescriptor.generateFullMethodName(
                                method.getService().getFullName(), method.getName()))
                        .setRequestMarshaller(ProtoUtils.marshaller(
                                DynamicMessage.getDefaultInstance(method.getInputType())))
                        .setResponseMarshaller(ProtoUtils.marshaller(defaultResponse))
                        .build();
        return ClientCalls.blockingUnaryCall(channel, grpcMethod,
                CallOptions.DEFAULT.withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS), request);
    }
}
