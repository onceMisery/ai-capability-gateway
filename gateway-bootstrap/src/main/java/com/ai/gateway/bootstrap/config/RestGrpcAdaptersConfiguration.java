package com.ai.gateway.bootstrap.config;

import com.ai.gateway.adapter.grpc.ClasspathGrpcMethodRegistry;
import com.ai.gateway.adapter.grpc.GrpcInvocationAdapter;
import com.ai.gateway.adapter.grpc.GrpcMethodRegistry;
import com.ai.gateway.adapter.grpc.GrpcUnaryClient;
import com.ai.gateway.adapter.grpc.ManagedChannelGrpcUnaryClient;
import com.ai.gateway.adapter.rest.JdkRestHttpClient;
import com.ai.gateway.adapter.rest.RestEndpointResolver;
import com.ai.gateway.adapter.rest.RestHttpClient;
import com.ai.gateway.adapter.rest.RestInvocationAdapter;
import com.ai.gateway.domain.port.ManifestRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.util.Map;

@Configuration
public class RestGrpcAdaptersConfiguration {

    @Bean
    public RestEndpointResolver restEndpointResolver(GatewayProperties properties) {
        var endpoints = properties.getProtocol().getRestEndpoints();
        return ref -> URI.create(requiredEndpoint(endpoints, ref, "REST"));
    }

    @Bean
    public RestHttpClient restHttpClient() {
        return new JdkRestHttpClient();
    }

    @Bean
    public RestInvocationAdapter restInvocationAdapter(
            ManifestRepository manifestRepository,
            RestEndpointResolver endpointResolver,
            RestHttpClient httpClient,
            ObjectMapper objectMapper) {
        return new RestInvocationAdapter(manifestRepository, endpointResolver,
                httpClient, objectMapper);
    }

    @Bean
    public GrpcMethodRegistry grpcMethodRegistry(GatewayProperties properties) {
        return new ClasspathGrpcMethodRegistry(
                properties.getProtocol().getGrpcDescriptorSets());
    }

    @Bean
    public GrpcUnaryClient grpcUnaryClient(GatewayProperties properties) {
        var endpoints = properties.getProtocol().getGrpcEndpoints();
        return new ManagedChannelGrpcUnaryClient(ref -> {
            String target = requiredEndpoint(endpoints, ref, "gRPC");
            URI uri = URI.create(target.contains("://") ? target : "dns:///" + target);
            NettyChannelBuilder builder = NettyChannelBuilder.forAddress(
                    uri.getHost(), uri.getPort() > 0 ? uri.getPort() : 443);
            return uri.getScheme().equalsIgnoreCase("https")
                    ? builder.useTransportSecurity().build() : builder.usePlaintext().build();
        });
    }

    @Bean
    public GrpcInvocationAdapter grpcInvocationAdapter(
            ManifestRepository manifestRepository,
            GrpcMethodRegistry methodRegistry,
            GrpcUnaryClient unaryClient,
            ObjectMapper objectMapper) {
        return new GrpcInvocationAdapter(manifestRepository, methodRegistry,
                unaryClient, objectMapper);
    }

    private static String requiredEndpoint(
            Map<String, String> endpoints, String ref, String protocol) {
        String endpoint = endpoints.get(ref);
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException(protocol
                    + " endpoint reference is not configured: " + ref);
        }
        return endpoint;
    }
}
