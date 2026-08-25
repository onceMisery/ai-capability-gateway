package com.ai.gateway.adapter.mcp;

import com.ai.gateway.domain.port.TelemetryPort;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class BoundedMcpCallExecutorTest {

    @Test
    void rejectsWhenWorkerAndQueueAreFull() throws Exception {
        ThreadPoolExecutor pool = pool();
        pool.prestartAllCoreThreads();
        CountDownLatch release = new CountDownLatch(1);
        pool.submit(() -> await(release));
        pool.submit(() -> await(release));
        BoundedMcpCallExecutor executor = new BoundedMcpCallExecutor(
                pool, mock(TelemetryPort.class));
        try {
            assertThatThrownBy(() -> executor.execute(
                    () -> "never", System.nanoTime() + TimeUnit.SECONDS.toNanos(1))
                    .block(Duration.ofSeconds(1)))
                    .isInstanceOf(java.util.concurrent.RejectedExecutionException.class);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void enforcesDeadlineWhileTaskIsRunning() {
        ThreadPoolExecutor pool = pool();
        pool.prestartAllCoreThreads();
        AtomicBoolean interrupted = new AtomicBoolean();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        BoundedMcpCallExecutor executor = new BoundedMcpCallExecutor(
                pool, mock(TelemetryPort.class));
        try {
            assertThatThrownBy(() -> executor.execute(() -> {
                started.countDown();
                try {
                    Thread.sleep(5_000L);
                } catch (InterruptedException e) {
                    interrupted.set(true);
                    cancelled.countDown();
                    throw e;
                }
                return "late";
            }, System.nanoTime() + TimeUnit.SECONDS.toNanos(2))
                    .block(Duration.ofSeconds(3)))
                    .hasRootCauseInstanceOf(java.util.concurrent.TimeoutException.class);
            assertThat(started.await(1L, TimeUnit.SECONDS)).isTrue();
            assertThat(cancelled.await(1L, TimeUnit.SECONDS)).isTrue();
            assertThat(interrupted).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        } finally {
            pool.shutdownNow();
        }
    }

    private static ThreadPoolExecutor pool() {
        return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1), new ThreadPoolExecutor.AbortPolicy());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
