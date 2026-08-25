package com.ai.gateway.adapter.mcp;

import com.ai.gateway.domain.port.TelemetryPort;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 目录或策略纪元变化时，向本节点活跃 MCP 会话推送 {@code notifications/tools/list_changed}。
 *
 * <p>直投模式下客户端会长期持有一份 {@code tools/list} 快照。若缺少失效推送，撤权后客户端
 * 仍会展示已失效的工具定义，与「{@code policyEpoch} 失效不得只依赖 TTL」的约束冲突。
 * 本类补上这条推送。</p>
 *
 * <p><b>推送是体验优化，不是安全机制。</b>唯一权威的失效判定发生在执行期：
 * {@code AgentToolProjectionUseCase.bind} 每次都按当前授权集合现场重建 alias 反查表，
 * 撤权后 alias 直接不在表内。因此本类不维护任何 alias 缓存，也就没有「广播失败后需要
 * 清理缓存」这一步——<b>失效关闭是结构性的，而不是靠一次成功的通知换来的</b>。广播失败
 * （会话已断、客户端不支持该通知）只记录埋点，不影响正确性。</p>
 *
 * <p>触发信号取 {@link Epoch}：{@code catalogVersion} 与全局 {@code policyEpoch} 的组合，
 * 两者都可在本节点直接观测（前者来自内存目录，后者来自
 * {@code AuthorizationPort.currentPolicyEpoch()}），无需持有任何 Principal。
 * 单个主体的授权变化若未抬升全局 epoch，则不会触发推送——这正是上一段所述的情形，
 * 由执行期重新鉴权兜住。</p>
 *
 * <p>推送按节点隔离：{@link SessionNotifier} 的实现只遍历本节点自己建立的会话，
 * 跨节点会话由各自节点的实例负责。</p>
 *
 * <p>本类线程安全：纪元比较用 CAS 完成，重复或并发触发只会推送一次。</p>
 *
 * @author cmiracle@163.com
 * @since 0.1.0
 */
public final class McpToolListChangeBroadcaster implements AutoCloseable {

    /** MCP 规范定义的工具清单变更通知方法名。 */
    public static final String METHOD = "notifications/tools/list_changed";

    private final EpochSource epochSource;
    private final SessionNotifier sessionNotifier;
    private final McpRateLimiter rateLimiter;
    private final TelemetryPort telemetry;
    private final AtomicReference<Epoch> lastBroadcast = new AtomicReference<>();
    private final AtomicReference<ScheduledExecutorService> watcher = new AtomicReference<>();

    /**
     * 构造广播器。
     *
     * @param epochSource     纪元观测源，不能为 {@code null}
     * @param sessionNotifier 本节点会话推送器，不能为 {@code null}
     * @param rateLimiter     限流器，不能为 {@code null}
     * @param telemetry       遥测端口，不能为 {@code null}
     */
    public McpToolListChangeBroadcaster(EpochSource epochSource,
                                        SessionNotifier sessionNotifier,
                                        McpRateLimiter rateLimiter,
                                        TelemetryPort telemetry) {
        this.epochSource = Objects.requireNonNull(epochSource, "epochSource must not be null");
        this.sessionNotifier = Objects.requireNonNull(
                sessionNotifier, "sessionNotifier must not be null");
        this.rateLimiter = Objects.requireNonNull(rateLimiter, "rateLimiter must not be null");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry must not be null");
    }

    /**
     * 比较当前纪元与上次已广播的纪元，仅在变化时推送一次。
     *
     * <p>首次调用会把当前纪元记为基线而<b>不</b>推送：启动瞬间的会话本就要主动拉取
     * {@code tools/list}，此时推送没有信息量，只会在每次实例重启时给所有客户端造成一次
     * 无谓的重新拉取。</p>
     *
     * @return 本次是否真的发出了推送
     */
    public boolean broadcastIfChanged() {
        Epoch current = readEpoch();
        if (current == null) {
            return false;
        }
        Epoch previous = lastBroadcast.get();
        if (previous == null) {
            // 记基线失败说明有并发调用抢先记录，交给对方判断即可。
            lastBroadcast.compareAndSet(null, current);
            return false;
        }
        if (current.equals(previous) || !lastBroadcast.compareAndSet(previous, current)) {
            return false;
        }
        if (!rateLimiter.tryAcquire(McpRateLimiter.NOTIFY)) {
            telemetry.increment("gateway.mcp.projection.notify",
                    Map.of("outcome", "rate_limited"));
            return false;
        }
        return broadcast();
    }

    /**
     * 无条件推送一次，并把当前纪元记为基线。
     *
     * <p>供确定已发生变更的调用方（如控制面发布回调）直接触发，跳过纪元比较。</p>
     *
     * @return 是否成功发出推送
     */
    public boolean broadcastNow() {
        Epoch current = readEpoch();
        if (current != null) {
            lastBroadcast.set(current);
        }
        return broadcast();
    }

    /**
     * 启动周期性纪元观测。
     *
     * <p>之所以用观测而非事件回调：目录激活既可能来自本节点的控制面发布，也可能来自
     * Redis 通知触发的热加载，而 {@code InMemoryCatalogManager} 未提供激活回调钩子。
     * 观测同一个可见状态可以统一覆盖这两条路径，且不需要修改既有类（开闭原则）。</p>
     *
     * @param interval 观测间隔，必须为正
     * @throws IllegalStateException 已启动时抛出
     */
    public void start(Duration interval) {
        Objects.requireNonNull(interval, "interval must not be null");
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("interval must be positive");
        }
        ScheduledExecutorService created = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "gateway-mcp-toollist-watcher");
            thread.setDaemon(true);
            return thread;
        });
        if (!watcher.compareAndSet(null, created)) {
            created.shutdownNow();
            throw new IllegalStateException("broadcaster is already started");
        }
        long millis = Math.max(100L, interval.toMillis());
        created.scheduleAtFixedRate(this::tick, millis, millis, TimeUnit.MILLISECONDS);
    }

    /** 停止观测线程；未启动时为空操作，可重复调用。 */
    @Override
    public void close() {
        ScheduledExecutorService running = watcher.getAndSet(null);
        if (running != null) {
            running.shutdownNow();
        }
    }

    /** 观测线程绝不能因单次异常而终止，否则失效推送会静默停摆。 */
    private void tick() {
        try {
            broadcastIfChanged();
        } catch (RuntimeException e) {
            telemetry.increment("gateway.mcp.projection.notify",
                    Map.of("outcome", "watch_failed"));
        }
    }

    private Epoch readEpoch() {
        try {
            return epochSource.current();
        } catch (RuntimeException e) {
            telemetry.increment("gateway.mcp.projection.notify",
                    Map.of("outcome", "epoch_unavailable"));
            return null;
        }
    }

    private boolean broadcast() {
        try {
            int notified = sessionNotifier.notifyToolListChanged();
            telemetry.increment("gateway.mcp.projection.notify",
                    Map.of("outcome", "broadcast"));
            telemetry.recordValue("gateway.mcp.projection.notified", notified,
                    Map.of("resource", "sse"));
            return true;
        } catch (RuntimeException e) {
            // 广播失败不影响正确性：执行期重新鉴权是唯一权威判定。
            telemetry.increment("gateway.mcp.projection.notify",
                    Map.of("outcome", "broadcast_failed"));
            return false;
        }
    }

    /**
     * 触发广播的纪元对。
     *
     * @param catalogVersion 当前生效的目录快照版本
     * @param policyEpoch    当前生效的全局策略纪元
     */
    public record Epoch(long catalogVersion, long policyEpoch) {
    }

    /** 纪元观测源，实现应读取本节点当前生效的值，不得阻塞。 */
    @FunctionalInterface
    public interface EpochSource {

        /**
         * @return 当前纪元；无法确定时可返回 {@code null}，本次观测将被跳过
         */
        Epoch current();
    }

    /** 本节点会话推送器，实现必须排除非本节点的会话。 */
    @FunctionalInterface
    public interface SessionNotifier {

        /**
         * 向本节点所有活跃会话推送 {@link #METHOD} 通知。
         *
         * @return 收到推送的会话数
         */
        int notifyToolListChanged();
    }
}
