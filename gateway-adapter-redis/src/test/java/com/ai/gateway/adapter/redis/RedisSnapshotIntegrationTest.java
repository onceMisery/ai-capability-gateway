package com.ai.gateway.adapter.redis;

import com.ai.gateway.domain.model.CatalogSnapshot;
import com.ai.gateway.domain.port.CatalogPort;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
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
import static org.mockito.Mockito.when;

/**
 * Integration tests for the Redis snapshot infrastructure (milestone M2/M5).
 *
 * <p>Runs against a real Redis started by Testcontainers and verifies the
 * end-to-end behaviors that unit tests with mocks cannot:</p>
 * <ul>
 * <li>Pub/Sub notification delivery between a publisher and a subscriber
 * (the multi-instance hot-reload path).</li>
 * <li>Write-Through caching: a snapshot saved through the decorator is
 * served from Redis on the next read without hitting PostgreSQL.</li>
 * </ul>
 *
 * <p>Disabled by default — requires Docker. Enable in CI where Docker is
 * available (mirrors {@code IntegrationTestSkeleton} in gateway-bootstrap).</p>
 */
@Disabled("Requires Docker/Testcontainers - enable in CI")
@Testcontainers
class RedisSnapshotIntegrationTest {

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
    @DisplayName("pub/sub delivers published snapshot version to subscribers")
    void pubSubRoundTrip() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> received = new AtomicReference<>();

        RTopic topic = redisson.getTopic(RedisKeys.CHANNEL_SNAPSHOT_PUBLISHED);
        int listenerId = topic.addListener(String.class,
                (MessageListener<String>) (channel, msg) -> {
                    received.set(msg);
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
    @DisplayName("save is write-through: subsequent read is served from Redis")
    void writeThroughServesFromRedis() {
        CatalogPort postgres = mock(CatalogPort.class);
        CatalogSnapshot snapshot =
                new CatalogSnapshot(7, ENV, List.of(), "policy-v7", "");
        when(postgres.loadCurrentSnapshot(ENV)).thenReturn(snapshot);

        RedisCatalogPortDecorator decorator = new RedisCatalogPortDecorator(
                postgres, redisson,
                new com.fasterxml.jackson.databind.ObjectMapper(), 30);

        // First read: Redis miss -> PostgreSQL -> back-fill Redis.
        CatalogSnapshot first = decorator.loadCurrentSnapshot(ENV);
        assertThat(first.snapshotVersion()).isEqualTo(7);

        // A second decorator (simulating another instance, empty L1) reads
        // the same snapshot straight from Redis without touching PostgreSQL.
        RedisCatalogPortDecorator otherInstance = new RedisCatalogPortDecorator(
                mock(CatalogPort.class), redisson,
                new com.fasterxml.jackson.databind.ObjectMapper(), 30);
        CatalogSnapshot second = otherInstance.loadCurrentSnapshot(ENV);
        assertThat(second.snapshotVersion()).isEqualTo(7);
        assertThat(second.policyRef()).isEqualTo("policy-v7");
    }
}
