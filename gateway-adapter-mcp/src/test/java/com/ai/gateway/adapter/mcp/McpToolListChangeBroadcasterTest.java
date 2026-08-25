package com.ai.gateway.adapter.mcp;

import com.ai.gateway.application.resilience.RateLimiterManager;
import com.ai.gateway.domain.port.TelemetryPort;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Acceptance tests for tool-list invalidation pushes (design §4.5).
 *
 * <p>The broadcaster is deliberately an experience optimisation, not a security
 * mechanism: authorization is re-decided at execution time, so a dropped, rate-limited
 * or failed notification may never turn into an error the caller has to handle. These
 * tests therefore pin two things — one push per epoch change, and no failure mode that
 * escapes to the caller or kills the watcher thread.</p>
 *
 * @author cmiracle@163.com
 */
class McpToolListChangeBroadcasterTest {

    @Test
    void firstObservationOnlyRecordsTheBaselineSoRestartsDoNotStampedeClients() {
        Fixture fixture = Fixture.create();

        boolean broadcast = fixture.broadcaster.broadcastIfChanged();

        assertThat(broadcast).isFalse();
        assertThat(fixture.notifications.get()).isZero();
        verifyNoInteractions(fixture.telemetry);
    }

    @Test
    void unchangedEpochNeverProducesASecondPush() {
        Fixture fixture = Fixture.create();
        fixture.broadcaster.broadcastIfChanged();

        assertThat(fixture.broadcaster.broadcastIfChanged()).isFalse();
        assertThat(fixture.broadcaster.broadcastIfChanged()).isFalse();

        assertThat(fixture.notifications.get()).isZero();
    }

    @Test
    void catalogPublishAndPolicyChangeEachTriggerExactlyOnePush() {
        Fixture fixture = Fixture.create();
        fixture.broadcaster.broadcastIfChanged();

        fixture.epoch.set(new McpToolListChangeBroadcaster.Epoch(10L, 5L));
        boolean afterPublish = fixture.broadcaster.broadcastIfChanged();
        boolean repeated = fixture.broadcaster.broadcastIfChanged();
        fixture.epoch.set(new McpToolListChangeBroadcaster.Epoch(10L, 6L));
        boolean afterPolicyChange = fixture.broadcaster.broadcastIfChanged();

        assertThat(afterPublish).isTrue();
        assertThat(repeated).isFalse();
        assertThat(afterPolicyChange).isTrue();
        assertThat(fixture.notifications.get()).isEqualTo(2);
        verify(fixture.telemetry, org.mockito.Mockito.times(2))
                .increment("gateway.mcp.projection.notify", Map.of("outcome", "broadcast"));
        verify(fixture.telemetry, org.mockito.Mockito.times(2))
                .recordValue("gateway.mcp.projection.notified", 3L,
                        Map.of("resource", "sse"));
    }

    @Test
    void rateLimitedPushIsDroppedWithoutRetryingAndWithoutFailingTheObserver() {
        Fixture fixture = Fixture.withLimiter(McpRateLimiter.from(
                new RateLimiterManager((dimension, key, permits) -> false)));
        fixture.broadcaster.broadcastIfChanged();
        fixture.epoch.set(new McpToolListChangeBroadcaster.Epoch(10L, 5L));

        boolean dropped = fixture.broadcaster.broadcastIfChanged();
        // 纪元在限流判断之前就已推进，因此被限流的那次变更不会稍后补发。
        // 这是刻意的：补发队列会把目录抖动放大成会话风暴，而失效关闭本来就由执行期鉴权兜住。
        boolean notRetried = fixture.broadcaster.broadcastIfChanged();

        assertThat(dropped).isFalse();
        assertThat(notRetried).isFalse();
        assertThat(fixture.notifications.get()).isZero();
        verify(fixture.telemetry).increment("gateway.mcp.projection.notify",
                Map.of("outcome", "rate_limited"));
    }

    @Test
    void pushFailureIsRecordedButNeverPropagatesToTheObserver() {
        Fixture fixture = Fixture.create();
        fixture.broadcaster.broadcastIfChanged();
        fixture.notifierFailure.set(new IllegalStateException("session already closed"));
        fixture.epoch.set(new McpToolListChangeBroadcaster.Epoch(10L, 5L));

        boolean broadcast = fixture.broadcaster.broadcastIfChanged();

        assertThat(broadcast).isFalse();
        verify(fixture.telemetry).increment("gateway.mcp.projection.notify",
                Map.of("outcome", "broadcast_failed"));
        verify(fixture.telemetry, never()).increment("gateway.mcp.projection.notify",
                Map.of("outcome", "broadcast"));
    }

    @Test
    void unreadableEpochSkipsTheObservationInsteadOfPushingBlindly() {
        AtomicInteger notifications = new AtomicInteger();
        TelemetryPort telemetry = mock(TelemetryPort.class);
        McpToolListChangeBroadcaster broadcaster = new McpToolListChangeBroadcaster(
                () -> {
                    throw new IllegalStateException("catalog is reloading");
                },
                notifications::incrementAndGet, McpRateLimiter.allowAll(), telemetry);

        assertThat(broadcaster.broadcastIfChanged()).isFalse();

        assertThat(notifications.get()).isZero();
        verify(telemetry).increment("gateway.mcp.projection.notify",
                Map.of("outcome", "epoch_unavailable"));
    }

    @Test
    void undeterminedEpochIsSkippedSilentlyRatherThanCountedAsAFailure() {
        AtomicInteger notifications = new AtomicInteger();
        TelemetryPort telemetry = mock(TelemetryPort.class);
        McpToolListChangeBroadcaster broadcaster = new McpToolListChangeBroadcaster(
                () -> null, notifications::incrementAndGet,
                McpRateLimiter.allowAll(), telemetry);

        assertThat(broadcaster.broadcastIfChanged()).isFalse();

        assertThat(notifications.get()).isZero();
        verifyNoInteractions(telemetry);
    }

    @Test
    void broadcastNowSkipsTheComparisonAndRebasesTheBaseline() {
        Fixture fixture = Fixture.create();

        boolean forced = fixture.broadcaster.broadcastNow();
        // 强制推送同时把当前纪元记为基线，随后的观测不会再重复推一次。
        boolean followUp = fixture.broadcaster.broadcastIfChanged();

        assertThat(forced).isTrue();
        assertThat(followUp).isFalse();
        assertThat(fixture.notifications.get()).isEqualTo(1);
    }

    @Test
    void concurrentObserversOfTheSameChangePushExactlyOnce() throws Exception {
        Fixture fixture = Fixture.create();
        fixture.broadcaster.broadcastIfChanged();
        fixture.epoch.set(new McpToolListChangeBroadcaster.Epoch(10L, 5L));
        int observers = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(observers);
        AtomicInteger broadcasts = new AtomicInteger();
        for (int i = 0; i < observers; i++) {
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    if (fixture.broadcaster.broadcastIfChanged()) {
                        broadcasts.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            thread.setDaemon(true);
            thread.start();
        }
        start.countDown();

        assertThat(done.await(5L, TimeUnit.SECONDS)).isTrue();
        assertThat(broadcasts.get()).isEqualTo(1);
        assertThat(fixture.notifications.get()).isEqualTo(1);
    }

    @Test
    void watcherObservesEpochChangesOnItsOwnThreadAndSurvivesAFailedTick() throws Exception {
        CountDownLatch notified = new CountDownLatch(1);
        AtomicInteger observations = new AtomicInteger();
        McpToolListChangeBroadcaster broadcaster = new McpToolListChangeBroadcaster(
                () -> {
                    // 观测序列固定为「基线 → 抛异常 → 纪元已变」，不依赖挂钟时序：
                    // 第二次的异常必须被观测线程吞掉，否则失效推送会静默停摆。
                    int observation = observations.incrementAndGet();
                    if (observation == 2) {
                        throw new IllegalStateException("transient catalog error");
                    }
                    return observation < 3
                            ? new McpToolListChangeBroadcaster.Epoch(9L, 5L)
                            : new McpToolListChangeBroadcaster.Epoch(10L, 5L);
                },
                () -> {
                    notified.countDown();
                    return 1;
                },
                McpRateLimiter.allowAll(), mock(TelemetryPort.class));
        try {
            broadcaster.start(Duration.ofMillis(100L));

            assertThat(notified.await(5L, TimeUnit.SECONDS)).isTrue();
        } finally {
            broadcaster.close();
        }
    }

    @Test
    void startingTwiceIsRejectedAndCloseIsIdempotent() {
        Fixture fixture = Fixture.create();
        fixture.broadcaster.start(Duration.ofMillis(100L));
        try {
            assertThatThrownBy(() -> fixture.broadcaster.start(Duration.ofMillis(100L)))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            fixture.broadcaster.close();
            fixture.broadcaster.close();
        }
    }

    @Test
    void nonPositiveWatchIntervalIsRejectedRatherThanBusyLooping() {
        Fixture fixture = Fixture.create();

        assertThatThrownBy(() -> fixture.broadcaster.start(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fixture.broadcaster.start(Duration.ofMillis(-1L)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void notificationMethodStaysTheNameDefinedByTheMcpSpecification() {
        assertThat(McpToolListChangeBroadcaster.METHOD)
                .isEqualTo("notifications/tools/list_changed");
    }

    /** Mutable epoch + counting notifier, so a change can be simulated between calls. */
    private record Fixture(McpToolListChangeBroadcaster broadcaster,
                           AtomicReference<McpToolListChangeBroadcaster.Epoch> epoch,
                           AtomicInteger notifications,
                           AtomicReference<RuntimeException> notifierFailure,
                           TelemetryPort telemetry) {

        static Fixture create() {
            return withLimiter(McpRateLimiter.allowAll());
        }

        static Fixture withLimiter(McpRateLimiter limiter) {
            AtomicReference<McpToolListChangeBroadcaster.Epoch> epoch = new AtomicReference<>(
                    new McpToolListChangeBroadcaster.Epoch(9L, 5L));
            AtomicInteger notifications = new AtomicInteger();
            AtomicReference<RuntimeException> failure = new AtomicReference<>();
            TelemetryPort telemetry = mock(TelemetryPort.class);
            McpToolListChangeBroadcaster broadcaster = new McpToolListChangeBroadcaster(
                    epoch::get,
                    () -> {
                        RuntimeException pending = failure.get();
                        if (pending != null) {
                            throw pending;
                        }
                        notifications.incrementAndGet();
                        return 3;
                    },
                    limiter, telemetry);
            return new Fixture(broadcaster, epoch, notifications, failure, telemetry);
        }
    }
}
