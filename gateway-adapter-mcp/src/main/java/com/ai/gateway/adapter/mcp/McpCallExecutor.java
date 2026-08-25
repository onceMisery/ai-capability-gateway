package com.ai.gateway.adapter.mcp;

import reactor.core.publisher.Mono;

import java.util.concurrent.Callable;

/** Executes synchronous MCP tool work within the transport request deadline. */
public interface McpCallExecutor {

    <T> Mono<T> execute(Callable<T> task, long deadlineNanos);

    static McpCallExecutor direct() {
        return new McpCallExecutor() {
            @Override
            public <T> Mono<T> execute(Callable<T> task, long deadlineNanos) {
                return Mono.fromCallable(task);
            }
        };
    }
}
