package com.ai.gateway.adapter.grpc;

import com.ai.gateway.domain.model.ArgumentBinding;
import com.ai.gateway.domain.model.ArgumentSource;
import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.DeadlineBudget;
import com.ai.gateway.domain.model.InvocationRequest;
import com.ai.gateway.domain.model.Protocol;
import com.ai.gateway.domain.model.ProtocolBinding;
import com.ai.gateway.domain.model.SystemContext;
import com.ai.gateway.domain.port.ManifestRepository;
import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Message;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GrpcInvocationAdapterTest {

    @Test
    void buildsDynamicRequestAndConvertsDynamicResponse() throws Exception {
        ManifestRepository manifests = mock(ManifestRepository.class);
        GrpcMethodRegistry registry = mock(GrpcMethodRegistry.class);
        GrpcUnaryClient client = mock(GrpcUnaryClient.class);
        Descriptors.MethodDescriptor method = methodDescriptor();
        when(manifests.findByIdAndVersion("orders.query", "1.0.0"))
                .thenReturn(Optional.of(manifest(method)));
        when(registry.resolve("orders", "orders.OrderService", "GetOrder"))
                .thenReturn(method);
        when(client.unaryCall(eq("orders"), eq(method), any(DynamicMessage.class), eq(250L)))
                .thenAnswer(invocation -> {
                    DynamicMessage request = invocation.getArgument(2);
                    assertThat(request.getField(method.getInputType().findFieldByName("order_no")))
                            .isEqualTo("SO-1");
                    DynamicMessage.Builder response = DynamicMessage.newBuilder(method.getOutputType());
                    response.setField(method.getOutputType().findFieldByName("status"), "PAID");
                    return response.build();
                });

        GrpcInvocationAdapter adapter = new GrpcInvocationAdapter(
                manifests, registry, client, new com.fasterxml.jackson.databind.ObjectMapper());

        var result = adapter.invoke(new InvocationRequest("orders.query", "1.0.0",
                "digest", new DeadlineBudget(1000, 250), null,
                new SystemContext("trace-1", System.currentTimeMillis() + 250,
                        null, "zh-CN"), List.of(Map.of("order_no", "SO-1"))));

        assertThat(result.errorCode()).isNull();
        assertThat(result.jsonData()).isEqualTo(Map.of("status", "PAID"));
    }

    @Test
    void mapsGrpcTimeoutToProviderTimeout() throws Exception {
        ManifestRepository manifests = mock(ManifestRepository.class);
        GrpcMethodRegistry registry = mock(GrpcMethodRegistry.class);
        GrpcUnaryClient client = mock(GrpcUnaryClient.class);
        Descriptors.MethodDescriptor method = methodDescriptor();
        when(manifests.findByIdAndVersion("orders.query", "1.0.0"))
                .thenReturn(Optional.of(manifest(method)));
        when(registry.resolve("orders", "orders.OrderService", "GetOrder"))
                .thenReturn(method);
        when(client.unaryCall(any(), any(), any(), eq(250L)))
                .thenThrow(new io.grpc.StatusRuntimeException(io.grpc.Status.DEADLINE_EXCEEDED));

        GrpcInvocationAdapter adapter = new GrpcInvocationAdapter(
                manifests, registry, client, new com.fasterxml.jackson.databind.ObjectMapper());

        var result = adapter.invoke(new InvocationRequest("orders.query", "1.0.0",
                "digest", new DeadlineBudget(1000, 250), null,
                new SystemContext("trace-1", System.currentTimeMillis() + 250,
                        null, "zh-CN"), List.of(Map.of("order_no", "SO-1"))));

        assertThat(result.errorCode()).isEqualTo(com.ai.gateway.domain.model.ErrorCode.PROVIDER_TIMEOUT);
        assertThat(result.errorMessage()).isEqualTo("gRPC provider timed out");
    }

    private static Descriptors.MethodDescriptor methodDescriptor() throws Exception {
        DescriptorProtos.FileDescriptorProto file = DescriptorProtos.FileDescriptorProto.newBuilder()
                .setName("orders.proto").setPackage("orders")
                .addMessageType(DescriptorProtos.DescriptorProto.newBuilder().setName("GetOrderRequest")
                        .addField(field("order_no", 1, DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING)))
                .addMessageType(DescriptorProtos.DescriptorProto.newBuilder().setName("GetOrderResponse")
                        .addField(field("status", 1, DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING)))
                .addService(DescriptorProtos.ServiceDescriptorProto.newBuilder().setName("OrderService")
                        .addMethod(DescriptorProtos.MethodDescriptorProto.newBuilder()
                                .setName("GetOrder").setInputType(".orders.GetOrderRequest")
                                .setOutputType(".orders.GetOrderResponse")))
                .build();
        return Descriptors.FileDescriptor.buildFrom(file, new Descriptors.FileDescriptor[0])
                .findServiceByName("OrderService").findMethodByName("GetOrder");
    }

    private static DescriptorProtos.FieldDescriptorProto field(
            String name, int number, DescriptorProtos.FieldDescriptorProto.Type type) {
        return DescriptorProtos.FieldDescriptorProto.newBuilder()
                .setName(name).setNumber(number).setType(type).build();
    }

    private static ProtocolBinding binding(Descriptors.MethodDescriptor method) {
        return new ProtocolBinding(Protocol.GRPC, "orders", "orders.OrderService", null, null,
                method.getName(), List.of("orders.GetOrderRequest"), "protobuf",
                List.of(new ArgumentBinding(0, "request", "orders.GetOrderRequest",
                        ArgumentSource.MODEL, "/request", null, null, null)),
                Map.of());
    }

    private static CapabilityManifest manifest(Descriptors.MethodDescriptor method) {
        CapabilityManifest.Metadata metadata = new CapabilityManifest.Metadata(
                "orders.query", "1.0.0",
                new CapabilityManifest.Owner("orders", "orders@example.com"),
                List.of("orders"));
        CapabilityManifest.Spec spec = new CapabilityManifest.Spec(
                "query", "query", new CapabilityManifest.Examples(List.of(), List.of(), List.of()),
                com.ai.gateway.domain.model.RiskLevel.READ_ONLY, Map.of(), null, binding(method),
                org.mockito.Mockito.mock(com.ai.gateway.domain.model.OutputContract.class),
                org.mockito.Mockito.mock(com.ai.gateway.domain.model.ResiliencePolicy.class));
        return new CapabilityManifest("gateway.ai/v1", "Capability", metadata, spec);
    }
}
