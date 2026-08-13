package com.ai.gateway.adapter.redis;

import com.ai.gateway.domain.port.DistributedLockPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RedissonDistributedLockAdapter}.
 *
 * <p>Verifies lock acquisition/release delegation to Redisson's
 * {@code RLock} using a mocked client (no running Redis required), including
 * the {@code withLock} template behavior.</p>
 */
class RedissonDistributedLockAdapterTest {

    private RedissonClient redissonClient;
    private RLock lock;
    private RedissonDistributedLockAdapter adapter;

    @BeforeEach
    void setUp() {
        redissonClient = mock(RedissonClient.class);
        lock = mock(RLock.class);
        doReturn(lock).when(redissonClient).getLock("gateway:lock:publish");
        adapter = new RedissonDistributedLockAdapter(redissonClient);
    }

    @Test
    @DisplayName("tryLock delegates to RLock and returns acquisition result")
    void tryLockDelegates() throws InterruptedException {
        when(lock.tryLock(anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(true);

        assertThat(adapter.tryLock("publish", 100, 1000)).isTrue();
        verify(lock).tryLock(100, 1000, TimeUnit.MILLISECONDS);
    }

    @Test
    @DisplayName("unlock releases only when held by current thread")
    void unlockReleasesWhenHeld() {
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        adapter.unlock("publish");

        verify(lock).unlock();
    }

    @Test
    @DisplayName("unlock is a no-op when not held by current thread")
    void unlockNoOpWhenNotHeld() {
        when(lock.isHeldByCurrentThread()).thenReturn(false);

        adapter.unlock("publish");

        verify(lock, never()).unlock();
    }

    @Test
    @DisplayName("withLock runs the action and releases the lock")
    void withLockRunsAndReleases() throws InterruptedException {
        when(lock.tryLock(anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        String result = adapter.withLock("publish", 100, 1000, () -> "done");

        assertThat(result).isEqualTo("done");
        verify(lock).unlock();
    }

    @Test
    @DisplayName("withLock throws when the lock cannot be acquired")
    void withLockThrowsWhenNotAcquired() throws InterruptedException {
        when(lock.tryLock(anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(false);

        assertThatThrownBy(() -> adapter.withLock("publish", 100, 1000, () -> "x"))
                .isInstanceOf(DistributedLockPort.LockAcquisitionException.class);
    }
}
