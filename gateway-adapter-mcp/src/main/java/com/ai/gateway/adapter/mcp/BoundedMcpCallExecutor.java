package com.ai.gateway.adapter.mcp;

import com.ai.gateway.domain.port.TelemetryPort;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;

/** Executor-backed MCP call boundary with queue rejection, timeout and cancellation. */
public final class BoundedMcpCallExecutor implements McpCallExecutor {

    private final Executor executor;
    private final TelemetryPort telemetry;

    public BoundedMcpCallExecutor(Executor executor, TelemetryPort telemetry) {
        this.executor = Objects.requireNonNull(executor);
        this.telemetry = Objects.requireNonNull(telemetry);
    }

    @Override
    public <T> Mono<T> execute(Callable<T> task, long deadlineNanos) {
        Objects.requireNonNull(task);
        return Mono.defer(() -> {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0L) {
                increment("timeout");
                return Mono.error(new TimeoutException("MCP call deadline expired"));
            }
            return Mono.<T>create(sink -> {
                            FutureTask<Void> submitted = new FutureTask<>(() -> {
                                try {
                                    sink.success(task.call());
                                } catch (Throwable e) {
                                    sink.error(e);
                                }
                                return null;
                            });
                            sink.onCancel(() -> submitted.cancel(true));
                            try {
                                executor.execute(submitted);
                            } catch (RejectedExecutionException e) {
                                increment("capacity_rejected");
                                sink.error(e);
                            }
                    })
                    .timeout(Duration.ofNanos(remainingNanos))
                    .doOnSuccess(ignored -> increment("completed"))
                    .doOnError(TimeoutException.class, ignored -> increment("timeout"))
                    .doOnCancel(() -> increment("cancelled"));
        });
    }

    private void increment(String outcome) {
        telemetry.increment("gateway.mcp.executor", Map.of("outcome", outcome));
    }
}
