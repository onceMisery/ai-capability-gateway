package com.ai.gateway.domain.model;

import java.util.Set;
import java.util.Collections;

/**
 * 可供 {@link ArgumentSource#SYSTEM} 参数绑定使用的平台执行上下文值。
 *
 * <p>将 {@code SYSTEM} 定义为受控参数来源，仅读取平台内建的白名单路径。清单不得声明
 * 新的系统变量，用户与模型也都不得写入执行上下文。白名单路径如下：</p>
 *
 * <ul>
 * <li>{@code /traceId} - 分布式追踪标识</li>
 * <li>{@code /deadlineEpochMs} - 请求的绝对截止时间（epoch 毫秒）</li>
 * <li>{@code /idempotencyKey} - 写操作的服务端生成幂等键；只读请求为 null</li>
 * <li>{@code /locale} - 用于国际化的请求语言区域</li>
 * </ul>
 *
 * <p>若某个能力引用了不存在的系统值，必须拒绝执行。</p>
 *
 * @param traceId 分布式追踪标识
 * @param deadlineEpochMs 绝对截止时间（epoch 毫秒）
 * @param idempotencyKey 服务端生成的幂等键；只读请求可为 null
 * @param locale 请求语言区域（如 "zh-CN"）
 * @since 0.1.0
 */
public record SystemContext(
        String traceId,
        long deadlineEpochMs,
        String idempotencyKey,
        String locale
) {
    /**
     * 允许的系统上下文路径的不可变集合。
     */
    private static final Set<String> ALLOWED_PATHS = Set.of(
            "/traceId",
            "/deadlineEpochMs",
            "/idempotencyKey",
            "/locale"
    );

    /**
     * 紧凑构造器，对必填字段执行 null 检查。
     *
     * @param traceId 分布式追踪标识
     * @param deadlineEpochMs 绝对截止时间（epoch 毫秒）
     * @param idempotencyKey 服务端生成的幂等键（可为 null）
     * @param locale 请求语言区域
     */
    public SystemContext {
        java.util.Objects.requireNonNull(traceId, "traceId must not be null");
        java.util.Objects.requireNonNull(locale, "locale must not be null");
    }

    /**
     * 返回允许的系统上下文路径的不可修改视图。
     *
     * @return SYSTEM 绑定可引用的白名单路径集合
     */
    public static Set<String> allowedPaths() {
        return Collections.unmodifiableSet(ALLOWED_PATHS);
    }
}
