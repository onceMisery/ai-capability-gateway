package com.ai.gateway.adapter.grpc;

import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.ErrorCode;
import com.ai.gateway.domain.model.InvocationRequest;
import com.ai.gateway.domain.model.InvocationResult;
import com.ai.gateway.domain.model.Protocol;
import com.ai.gateway.domain.model.ProtocolBinding;
import com.ai.gateway.domain.model.ValidationReport;
import com.ai.gateway.domain.port.InvocationAdapter;
import com.ai.gateway.domain.port.ManifestRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.util.JsonFormat;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@RequiredArgsConstructor
public class GrpcInvocationAdapter implements InvocationAdapter {

    private final ManifestRepository manifestRepository;
    private final GrpcMethodRegistry methodRegistry;
    private final GrpcUnaryClient unaryClient;
    private final ObjectMapper objectMapper;

    @Override
    public Protocol protocol() {
        return Protocol.GRPC;
    }

    @Override
    public ValidationReport validate(ProtocolBinding binding) {
        List<String> errors = new ArrayList<>();
        if (binding == null) return ValidationReport.failure(List.of("gRPC binding must not be null"));
        if (binding.protocol() != Protocol.GRPC) errors.add("Protocol must be GRPC");
        if (blank(binding.registryRef())) errors.add("gRPC endpoint reference must not be blank");
        if (blank(binding.interfaceName())) errors.add("gRPC service name must not be blank");
        if (blank(binding.method())) errors.add("gRPC method name must not be blank");
        if (binding.arguments().size() != binding.parameterTypes().size()) {
            errors.add("gRPC parameterTypes and arguments must have the same size");
        }
        return errors.isEmpty() ? ValidationReport.success() : ValidationReport.failure(errors);
    }

    @Override
    public InvocationResult invoke(InvocationRequest request) {
        long started = System.currentTimeMillis();
        try {
            CapabilityManifest manifest = manifestRepository
                    .findByIdAndVersion(request.capabilityId(), request.capabilityVersion())
                    .orElse(null);
            if (manifest == null) {
                return failure(ErrorCode.CAPABILITY_UNAVAILABLE,
                        "Published manifest not found", started, "CAPABILITY_NOT_FOUND");
            }
            ProtocolBinding binding = manifest.spec().invocation();
            ValidationReport validation = validate(binding);
            if (!validation.valid()) {
                return failure(ErrorCode.PROTOCOL_ERROR,
                        "gRPC binding validation failed", started, "INVALID_BINDING");
            }
            long deadlineMs = request.deadlineBudget().remainingMs();
            if (deadlineMs <= 0) {
                return failure(ErrorCode.PROVIDER_TIMEOUT,
                        "gRPC provider timed out", started, "DEADLINE_EXPIRED");
            }
            var method = methodRegistry.resolve(binding.registryRef(),
                    binding.interfaceName(), binding.method());
            DynamicMessage dynamicRequest = buildRequest(
                    method.getInputType(), request.boundArguments());
            DynamicMessage response = unaryClient.unaryCall(
                    binding.registryRef(), method, dynamicRequest, deadlineMs);
            Object json = objectMapper.readValue(
                    JsonFormat.printer().omittingInsignificantWhitespace().print(response),
                    Object.class);
            return new InvocationResult(json, "OK", null, null,
                    Map.of("durationMs", elapsed(started),
                            "service", binding.interfaceName(),
                            "method", binding.method()));
        } catch (StatusRuntimeException e) {
            return failure(classify(e.getStatus().getCode()),
                    stableMessage(e.getStatus().getCode()), started,
                    e.getStatus().getCode().name());
        } catch (Exception e) {
            log.warn("gRPC invocation failed: capability={}, reason={}",
                    request.capabilityId(), e.getClass().getSimpleName());
            return failure(ErrorCode.PROTOCOL_ERROR,
                    "gRPC provider invocation failed", started, "INVOCATION_FAILED");
        }
    }

    private DynamicMessage buildRequest(com.google.protobuf.Descriptors.Descriptor descriptor,
                                        List<Object> boundArguments) throws Exception {
        if (boundArguments.size() != 1) {
            throw new IllegalArgumentException("gRPC unary invocation requires one request argument");
        }
        DynamicMessage.Builder builder = DynamicMessage.newBuilder(descriptor);
        Object argument = boundArguments.get(0);
        if (argument instanceof Map<?, ?>) {
            JsonFormat.parser().ignoringUnknownFields().merge(
                    objectMapper.writeValueAsString(argument), builder);
        } else {
            var fields = descriptor.getFields();
            if (fields.size() != 1) {
                throw new IllegalArgumentException(
                        "Scalar gRPC request requires a message with one field");
            }
            builder.setField(fields.get(0), argument);
        }
        return builder.build();
    }

    private static ErrorCode classify(Status.Code code) {
        return switch (code) {
            case DEADLINE_EXCEEDED, UNAVAILABLE -> ErrorCode.PROVIDER_TIMEOUT;
            case RESOURCE_EXHAUSTED -> ErrorCode.RATE_LIMITED;
            case UNAUTHENTICATED, PERMISSION_DENIED -> ErrorCode.PERMISSION_DENIED;
            case INVALID_ARGUMENT, FAILED_PRECONDITION -> ErrorCode.PROVIDER_REJECTED;
            default -> ErrorCode.PROTOCOL_ERROR;
        };
    }

    private static String stableMessage(Status.Code code) {
        return switch (code) {
            case DEADLINE_EXCEEDED -> "gRPC provider timed out";
            case UNAVAILABLE -> "gRPC provider unavailable";
            case RESOURCE_EXHAUSTED -> "gRPC provider rate limit reached";
            case UNAUTHENTICATED, PERMISSION_DENIED ->
                    "gRPC provider rejected authorization";
            case INVALID_ARGUMENT, FAILED_PRECONDITION ->
                    "gRPC provider rejected the request";
            default -> "gRPC provider invocation failed";
        };
    }

    private static InvocationResult failure(ErrorCode code, String message,
                                            long started, String reason) {
        return new InvocationResult(null, "ERROR", code, message,
                Map.of("durationMs", elapsed(started), "reason", reason));
    }

    private static String elapsed(long started) {
        return String.valueOf(System.currentTimeMillis() - started);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
