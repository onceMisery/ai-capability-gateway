package com.ai.gateway.adapter.grpc;

import io.grpc.ManagedChannel;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CachingGrpcChannelResolverTest {

    @Test
    void reusesChannelAndClosesItWithTheRegistry() throws Exception {
        ManagedChannel channel = mock(ManagedChannel.class);
        when(channel.shutdown()).thenReturn(channel);
        when(channel.awaitTermination(5L, java.util.concurrent.TimeUnit.SECONDS))
                .thenReturn(true);
        AtomicInteger creations = new AtomicInteger();
        CachingGrpcChannelResolver resolver = new CachingGrpcChannelResolver(
                Map.of("orders", "dns:///orders:443"), target -> {
                    creations.incrementAndGet();
                    return channel;
                });

        assertThat(resolver.resolve("orders")).isSameAs(channel);
        assertThat(resolver.resolve("orders")).isSameAs(channel);
        assertThat(creations).hasValue(1);

        resolver.close();

        verify(channel).shutdown();
        verify(channel).awaitTermination(5L, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(resolver.channelCount()).isZero();
    }
}
