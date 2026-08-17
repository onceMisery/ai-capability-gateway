package com.ai.gateway.adapter.redis;

import com.ai.gateway.domain.model.CatalogSnapshot;
import com.ai.gateway.domain.port.CatalogPort;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.api.listener.MessageListener;
import org.redisson.config.Config;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = true)
class RedisSnapshotIT {

    private static final String ENV = "production";

    @Container
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private static RedissonClient redisson;

    @BeforeAll
    static void setUpRedisson() {
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + REDIS.getHost() + ":" + REDIS.getFirstMappedPort());
        redisson = Redisson.create(config);
    }

    @AfterAll
    static void tearDownRedisson() {
        if (redisson != null) {
            redisson.shutdown();
        }
    }

    @Test
    void pubSubDeliversPublishedSnapshotVersion() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> received = new AtomicReference<>();
        RTopic topic = redisson.getTopic(RedisKeys.CHANNEL_SNAPSHOT_PUBLISHED);
        int listenerId = topic.addListener(String.class,
                (MessageListener<String>) (channel, message) -> {
                    received.set(message);
                    latch.countDown();
                });
        try {
            new RedisSnapshotNotifier(redisson).notifySnapshotPublished(42);
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(received.get()).isEqualTo("42");
        } finally {
            topic.removeListener(listenerId);
        }
    }

    @Test
    void secondInstanceReadsWriteThroughSnapshotWithoutPostgresql() {
        CatalogPort postgres = mock(CatalogPort.class);
        CatalogSnapshot snapshot = new CatalogSnapshot(7, ENV, List.of(), "policy-v7", "digest");
        when(postgres.loadCurrentSnapshot(ENV)).thenReturn(snapshot);
        RedisCatalogPortDecorator first = new RedisCatalogPortDecorator(postgres, redisson,
                new com.fasterxml.jackson.databind.ObjectMapper(), 30);
        assertThat(first.loadCurrentSnapshot(ENV).snapshotVersion()).isEqualTo(7);

        CatalogPort unavailablePostgres = mock(CatalogPort.class);
        RedisCatalogPortDecorator second = new RedisCatalogPortDecorator(unavailablePostgres, redisson,
                new com.fasterxml.jackson.databind.ObjectMapper(), 30);
        assertThat(second.loadCurrentSnapshot(ENV)).isEqualTo(snapshot);
        long started = System.nanoTime();
        for (int i = 0; i < 1_000; i++) {
            assertThat(second.loadCurrentSnapshot(ENV)).isEqualTo(snapshot);
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        System.out.printf("PERF_BASELINE redisSnapshotHits=%d elapsedMs=%d%n", 1_000, elapsedMs);
        verify(unavailablePostgres, never()).loadCurrentSnapshot(ENV);
    }
}
