package com.ai.gateway.bootstrap.config;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;

class AgentResolveExecutorTest {

    @Test
    void createsExecutorWithExplicitBoundedQueueAndAbortPolicy() {
        GatewayProperties properties = new GatewayProperties();
        properties.getAgent().setResolveMaxConcurrent(2);
        properties.getAgent().setResolveMaxQueue(3);

        ExecutorService executor = new BeanConfig().agentResolveExecutor(properties);
        try {
            ThreadPoolExecutor threadPool = (ThreadPoolExecutor) executor;

            assertThat(threadPool.getCorePoolSize()).isEqualTo(2);
            assertThat(threadPool.getMaximumPoolSize()).isEqualTo(2);
            assertThat(threadPool.getQueue()).isInstanceOf(ArrayBlockingQueue.class);
            assertThat(threadPool.getQueue().remainingCapacity()).isEqualTo(3);
            assertThat(threadPool.getRejectedExecutionHandler())
                    .isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
        } finally {
            executor.shutdownNow();
        }
    }
}
