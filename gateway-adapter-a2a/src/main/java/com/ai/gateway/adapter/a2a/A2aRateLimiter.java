package com.ai.gateway.adapter.a2a;

import com.ai.gateway.application.resilience.RateLimiterManager;

import java.util.Objects;

/**
 * A2A 入站限流。
 *
 * <p>资源键按<b>暴露面</b>而非按 peer 划分，共三个：公开卡、扩展卡、Task。这样切分的原因是
 * 三者的滥用形态完全不同——公开卡是匿名可达的，扩展卡会触发目录投影与缓存计算，
 * Task 才会真正走到检索与执行。用一个键统一限流会让「匿名刷公开卡」把正常的 Task 配额挤掉。</p>
 *
 * <p>按 peer 的配额不在这里实现：{@link RateLimiterManager} 的维度键一旦带上 peer 指纹，
 * 限流器内部就会为每个 peer 建一个计数器，而 peer 集合来自不可信的入站连接，
 * 那等于把「制造无限维度」的能力交给对端。peer 级的保护由信任档案与准入控制承担。</p>
 *
 * <p>本类不可变且线程安全。</p>
 *
 * @author cmiracle@163.com
 * @since 0.1.0
 */
public final class A2aRateLimiter {

    /** 公开 AgentCard（{@code /.well-known/agent-card.json}）的资源键。 */
    public static final String PUBLIC_CARD = "a2a-public-card";

    /** 认证后扩展卡（{@code agent/getAuthenticatedExtendedCard}）的资源键。 */
    public static final String EXTENDED_CARD = "a2a-extended-card";

    /** 入站 Task（{@code message/send}）的资源键。 */
    public static final String TASK = "a2a-task";

    private static final String GLOBAL_KEY = "global";

    private final RateLimiterManager delegate;

    private A2aRateLimiter(RateLimiterManager delegate) {
        this.delegate = delegate;
    }

    /**
     * @param delegate 统一限流管理器，不能为 {@code null}
     * @return 委派到统一限流器的实例
     */
    public static A2aRateLimiter from(RateLimiterManager delegate) {
        return new A2aRateLimiter(Objects.requireNonNull(delegate, "delegate must not be null"));
    }

    /**
     * 返回恒放行的实例。
     *
     * <p>仅用于单元测试与未启用限流基础设施的本地部署；生产装配必须使用
     * {@link #from(RateLimiterManager)}。</p>
     *
     * @return 恒放行实例
     */
    public static A2aRateLimiter allowAll() {
        return new A2aRateLimiter(null);
    }

    /**
     * 尝试获取一个许可。
     *
     * @param operation 资源键，取本类的三个常量之一
     * @return 放行时返回 {@code true}
     */
    public boolean tryAcquire(String operation) {
        if (delegate == null) {
            return true;
        }
        return delegate.checkAndAcquire(operation, GLOBAL_KEY);
    }
}
