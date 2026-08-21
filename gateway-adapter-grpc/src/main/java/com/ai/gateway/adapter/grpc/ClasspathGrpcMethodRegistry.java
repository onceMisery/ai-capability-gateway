package com.ai.gateway.adapter.grpc;

import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;
import lombok.RequiredArgsConstructor;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

@RequiredArgsConstructor
public class ClasspathGrpcMethodRegistry implements GrpcMethodRegistry {

    private final Map<String, String> descriptorSets;
    private final Map<String, Map<String, Descriptors.MethodDescriptor>> cache = new HashMap<>();

    @Override
    public synchronized Descriptors.MethodDescriptor resolve(
            String registryRef, String serviceName, String methodName) {
        Map<String, Descriptors.MethodDescriptor> methods = cache.computeIfAbsent(
                registryRef, this::load);
        Descriptors.MethodDescriptor method = methods.get(serviceName + "/" + methodName);
        if (method == null) {
            throw new IllegalArgumentException(
                    "gRPC method is not present in the configured descriptor set");
        }
        if (method.isClientStreaming() || method.isServerStreaming()) {
            throw new IllegalArgumentException("Only unary gRPC methods are supported");
        }
        return method;
    }

    private Map<String, Descriptors.MethodDescriptor> load(String registryRef) {
        String location = descriptorSets.get(registryRef);
        if (location == null || location.isBlank()) {
            throw new IllegalArgumentException(
                    "gRPC descriptor set is not configured: " + registryRef);
        }
        try (InputStream input = open(location)) {
            DescriptorProtos.FileDescriptorSet set =
                    DescriptorProtos.FileDescriptorSet.parseFrom(input);
            Map<String, Descriptors.FileDescriptor> files = new HashMap<>();
            for (DescriptorProtos.FileDescriptorProto file : set.getFileList()) {
                files.put(file.getName(), Descriptors.FileDescriptor.buildFrom(
                        file, dependencies(file, set, files)));
            }
            Map<String, Descriptors.MethodDescriptor> methods = new HashMap<>();
            for (Descriptors.FileDescriptor file : files.values()) {
                for (Descriptors.ServiceDescriptor service : file.getServices()) {
                    for (Descriptors.MethodDescriptor method : service.getMethods()) {
                        methods.put(service.getFullName() + "/" + method.getName(), method);
                    }
                }
            }
            return Map.copyOf(methods);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Unable to load configured gRPC descriptor set", e);
        }
    }

    private InputStream open(String location) throws Exception {
        if (location.startsWith("classpath:")) {
            String resource = location.substring("classpath:".length());
            InputStream input = getClass().getResourceAsStream(resource);
            if (input == null) throw new IllegalArgumentException("Resource not found");
            return input;
        }
        return Files.newInputStream(Path.of(location));
    }

    private static Descriptors.FileDescriptor[] dependencies(
            DescriptorProtos.FileDescriptorProto file,
            DescriptorProtos.FileDescriptorSet set,
            Map<String, Descriptors.FileDescriptor> loaded) {
        return file.getDependencyList().stream()
                .map(name -> loaded.get(name))
                .filter(java.util.Objects::nonNull)
                .toArray(Descriptors.FileDescriptor[]::new);
    }
}
