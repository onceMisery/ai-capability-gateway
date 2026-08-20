package com.ai.gateway.adapter.redis;

import com.ai.gateway.domain.model.CatalogSnapshot;
import com.ai.gateway.domain.port.CatalogPort;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RedisCatalogPortDecorator} 的单元测试。
 *
 * <p>使用 mock 的 Redisson 客户端验证两级缓存行为（Caffeine L1 + Redis L2 +
 * PostgreSQL 事实来源），因此无需运行中的 Redis。</p>
 *
 * @author cmiracle@163.com
 */
class RedisCatalogPortDecoratorTest {

    private static final String ENV = "production";

    private CatalogPort delegate;
    private RedissonClient redissonClient;
    private RBucket<String> bucket;
    private ObjectMapper objectMapper;
    private RedisCatalogPortDecorator decorator;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        delegate = mock(CatalogPort.class);
        redissonClient = mock(RedissonClient.class);
        bucket = mock(RBucket.class);
        objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        doReturn(bucket).when(redissonClient).getBucket(anyString());
        decorator = new RedisCatalogPortDecorator(delegate, redissonClient, objectMapper, 30);
    }

    private CatalogSnapshot snapshot(long version) {
        return new CatalogSnapshot(version, ENV, List.of(), "policy-v" + version, "");
    }

    @Test
    @DisplayName("read misses Redis, loads from PostgreSQL and back-fills Redis")
    void readThroughBackFillsRedis() throws Exception {
        when(bucket.get()).thenReturn(null);
        when(delegate.loadCurrentSnapshot(ENV)).thenReturn(snapshot(7));

        CatalogSnapshot result = decorator.loadCurrentSnapshot(ENV);

        assertThat(result.snapshotVersion()).isEqualTo(7);
        verify(delegate, times(1)).loadCurrentSnapshot(ENV);
        verify(bucket, times(1)).set(anyString());
    }

    @Test
    @DisplayName("read hits Redis and does not touch PostgreSQL")
    void readHitsRedis() throws Exception {
        String json = objectMapper.writeValueAsString(snapshot(9));
        when(bucket.get()).thenReturn(json);

        CatalogSnapshot result = decorator.loadCurrentSnapshot(ENV);

        assertThat(result.snapshotVersion()).isEqualTo(9);
        verify(delegate, never()).loadCurrentSnapshot(ENV);
    }

    @Test
    @DisplayName("second read is served by the Caffeine L1 cache")
    void secondReadServedByL1() {
        when(bucket.get()).thenReturn(null);
        when(delegate.loadCurrentSnapshot(ENV)).thenReturn(snapshot(3));

        decorator.loadCurrentSnapshot(ENV);
        CatalogSnapshot second = decorator.loadCurrentSnapshot(ENV);

        assertThat(second.snapshotVersion()).isEqualTo(3);
        // PostgreSQL 仅命中一次；第二次调用由 L1 提供
        verify(delegate, times(1)).loadCurrentSnapshot(ENV);
    }

    @Test
    @DisplayName("save does not write Redis before the database transaction commits")
    void saveDoesNotWriteBeforeCommit() {
        CatalogSnapshot snapshot = snapshot(11);

        decorator.saveSnapshot(snapshot);

        verify(delegate, times(1)).saveSnapshot(snapshot);
        verify(bucket, never()).set(anyString());
    }

    @Test
    void publicationLockIsDelegatedToPostgresql() {
        decorator.lockEnvironmentForPublication(ENV);

        verify(delegate).lockEnvironmentForPublication(ENV);
    }

    @Test
    @DisplayName("publication event updates Redis after a committed transaction")
    void publicationEventUpdatesRedis() {
        CatalogSnapshot snapshot = snapshot(12);

        decorator.recordSnapshotPublication(snapshot, "MANIFEST_PUBLISHED");

        verify(delegate, times(1)).recordSnapshotPublication(snapshot, "MANIFEST_PUBLISHED");
        verify(bucket, times(1)).set(anyString());
    }

    @Test
    @DisplayName("publication cache write is deferred until after commit")
    void publicationCacheWriteIsDeferredUntilCommit() {
        CatalogSnapshot snapshot = snapshot(13);
        TransactionSynchronizationManager.initSynchronization();
        try {
            decorator.recordSnapshotPublication(snapshot, "MANIFEST_PUBLISHED");
            verify(bucket, never()).set(anyString());

            TransactionSynchronization synchronization =
                    TransactionSynchronizationManager.getSynchronizations().get(0);
            synchronization.afterCommit();
            verify(bucket, times(1)).set(anyString());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("Redis read failure degrades gracefully to PostgreSQL")
    void redisFailureDegradesToPostgres() {
        when(bucket.get()).thenThrow(new RuntimeException("redis down"));
        when(delegate.loadCurrentSnapshot(ENV)).thenReturn(snapshot(5));

        CatalogSnapshot result = decorator.loadCurrentSnapshot(ENV);

        assertThat(result.snapshotVersion()).isEqualTo(5);
        verify(delegate, times(1)).loadCurrentSnapshot(ENV);
    }

    @Test
    @DisplayName("invalidate clears the local L1 entry")
    void invalidateClearsL1() {
        when(bucket.get()).thenReturn(null);
        when(delegate.loadCurrentSnapshot(ENV)).thenReturn(snapshot(4));

        decorator.loadCurrentSnapshot(ENV);
        decorator.invalidate(ENV);
        decorator.loadCurrentSnapshot(ENV);

        // 失效后再次回源查询 PostgreSQL
        verify(delegate, times(2)).loadCurrentSnapshot(ENV);
        verify(bucket, times(1)).delete();
    }
}
