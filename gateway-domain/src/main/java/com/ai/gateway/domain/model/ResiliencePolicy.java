package com.ai.gateway.domain.model;

/**
 * 能力协议调用的韧性策略。
 *
 * <p>定义每个能力的韧性配置，这些值属于已确认清单的一部分，由适配器层强制执行：</p>
 *
 * <ul>
 * <li>{@code timeoutMs} - 单次 Provider 调用的最大耗时。不得超出调用时刻剩余的
 * 截止预算。</li>
 * <li>{@code retries} - 对可重试错误的最大重试次数。重试受风险等级约束：只读操作可按
 * 策略重试；写操作必须遵循两阶段恢复协议。</li>
 * <li>{@code maxConcurrent} - 限制该能力并行调用的并发舱（bulkhead）。</li>
 * <li>{@code circuitBreakerEnabled} - 是否对该能力启用熔断器模式，在 Provider 持续
 * 失败时提供快速失败行为。</li>
 * </ul>
 *
 * @param timeoutMs Provider 调用超时（毫秒）
 * @param retries 对可重试错误的最大重试次数
 * @param maxConcurrent 并发舱上限
 * @param circuitBreakerEnabled 熔断器是否启用
 * @since 0.1.0
 */
public record ResiliencePolicy(
        long timeoutMs,
        int retries,
        int maxConcurrent,
        boolean circuitBreakerEnabled
) {
}
