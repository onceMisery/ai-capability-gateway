package com.ai.gateway.adapter.grpc;

import io.grpc.ManagedChannel;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/** Reuses one managed channel per configured endpoint and owns channel shutdown. */
public final class CachingGrpcChannelResolver implements GrpcChannelResolver, AutoCloseable {

    private final Map<String, String> endpoints;
    private final Function<String, ManagedChannel> channelFactory;
    private final Map<String, ManagedChannel> channels = new ConcurrentHashMap<>();

    public CachingGrpcChannelResolver(
            Map<String, String> endpoints,
            Function<String, ManagedChannel> channelFactory) {
        this.endpoints = Map.copyOf(Objects.requireNonNull(endpoints));
        this.channelFactory = Objects.requireNonNull(channelFactory);
    }

    @Override
    public ManagedChannel resolve(String endpointRef) {
        String target = endpoints.get(endpointRef);
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException(
                    "gRPC endpoint reference is not configured: " + endpointRef);
        }
        return channels.computeIfAbsent(endpointRef, ignored -> {
            ManagedChannel channel = channelFactory.apply(target);
            if (channel == null) {
                throw new IllegalStateException("gRPC channel factory returned null");
            }
            return channel;
        });
    }

    int channelCount() {
        return channels.size();
    }

    @Override
    public void close() {
        channels.values().forEach(ManagedChannel::shutdown);
        channels.values().forEach(channel -> {
            try {
                if (!channel.awaitTermination(5L, TimeUnit.SECONDS)) {
                    channel.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                channel.shutdownNow();
            }
        });
        channels.clear();
    }
}
