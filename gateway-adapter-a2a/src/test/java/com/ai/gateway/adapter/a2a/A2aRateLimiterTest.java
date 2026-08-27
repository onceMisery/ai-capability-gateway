package com.ai.gateway.adapter.a2a;

import com.ai.gateway.application.resilience.RateLimiterManager;
import com.ai.gateway.domain.port.RateLimiterPort;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Acceptance tests for A2A inbound throttling (design §3.4, §3.10).
 *
 * <p>The point of this thin wrapper is the shape of the key it hands to the shared limiter:
 * one bucket per exposure surface, and never one per peer. These tests pin exactly that,
 * because the failure they guard against — an anonymous flood of public-card requests
 * consuming the Task budget, or an unbounded set of counters keyed by attacker-chosen peer
 * identities — is invisible in any test that only asserts "the call was throttled".</p>
 *
 * @author cmiracle@163.com
 */
class A2aRateLimiterTest {

    @Test
    void eachExposureSurfaceIsThrottledUnderItsOwnResourceKey() {
        RecordingPort port = new RecordingPort(true);
        A2aRateLimiter limiter = A2aRateLimiter.from(new RateLimiterManager(port));

        limiter.tryAcquire(A2aRateLimiter.PUBLIC_CARD);
        limiter.tryAcquire(A2aRateLimiter.EXTENDED_CARD);
        limiter.tryAcquire(A2aRateLimiter.TASK);

        // 三个暴露面的滥用形态互不相同，共用一个键会让「匿名刷公开卡」挤掉正常的 Task 配额。
        assertThat(port.dimensions)
                .containsExactly("a2a-public-card", "a2a-extended-card", "a2a-task");
        assertThat(port.dimensions).doesNotHaveDuplicates();
    }

    @Test
    void theLimiterKeyNeverVariesWithThePeerIdentity() {
        RecordingPort port = new RecordingPort(true);
        A2aRateLimiter limiter = A2aRateLimiter.from(new RateLimiterManager(port));

        limiter.tryAcquire(A2aRateLimiter.TASK);
        limiter.tryAcquire(A2aRateLimiter.TASK);

        // peer 集合来自不可信入站连接：把它放进维度键等于把「制造无限计数器」的能力交给对端。
        assertThat(port.keys).containsExactly("global", "global");
    }

    @Test
    void aRejectionFromTheSharedLimiterIsPropagatedRatherThanSwallowed() {
        A2aRateLimiter limiter = A2aRateLimiter.from(
                new RateLimiterManager(new RecordingPort(false)));

        assertThat(limiter.tryAcquire(A2aRateLimiter.TASK)).isFalse();
    }

    @Test
    void theAllowAllInstanceDoesNotTouchAnyLimiterAtAll() {
        A2aRateLimiter limiter = A2aRateLimiter.allowAll();

        assertThat(limiter.tryAcquire(A2aRateLimiter.TASK)).isTrue();
        assertThat(limiter.tryAcquire(A2aRateLimiter.PUBLIC_CARD)).isTrue();
    }

    @Test
    void aMissingDelegateIsRejectedAtWiringTimeInsteadOfDegradingSilently() {
        // 生产装配必须显式选择 allowAll()，不能因为漏传依赖而意外得到一个恒放行的限流器。
        assertThatThrownBy(() -> A2aRateLimiter.from(null))
                .isInstanceOf(NullPointerException.class);
    }

    /** 记录每次限流调用的维度与键，避免为 final 类引入 mock 机制。 */
    private static final class RecordingPort implements RateLimiterPort {

        private final List<String> dimensions = new ArrayList<>();
        private final List<String> keys = new ArrayList<>();
        private final boolean allow;

        private RecordingPort(boolean allow) {
            this.allow = allow;
        }

        @Override
        public boolean tryAcquire(String dimension, String key, int permits) {
            dimensions.add(dimension);
            keys.add(key);
            return allow;
        }
    }
}
