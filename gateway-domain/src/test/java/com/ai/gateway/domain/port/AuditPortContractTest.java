package com.ai.gateway.domain.port;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 审计端口契约测试，防止执行上下文在应用层与持久化适配器之间丢失。
 *
 * @author cmiracle@163.com
 */
class AuditPortContractTest {

    private static final String EXECUTION_AUDIT_CONTEXT =
            "com.ai.gateway.domain.model.ExecutionAuditContext";

    @Test
    void startedAndTerminalEventsMustUseTypedExecutionContext() {
        Method started = methodNamed("recordStarted");
        Method terminal = methodNamed("recordTerminal");

        assertThat(parameterTypeNames(started))
                .containsExactly(EXECUTION_AUDIT_CONTEXT);
        assertThat(parameterTypeNames(terminal))
                .containsExactly(EXECUTION_AUDIT_CONTEXT,
                        String.class.getName(), "long", String.class.getName());
    }

    private Method methodNamed(String methodName) {
        return Arrays.stream(AuditPort.class.getDeclaredMethods())
                .filter(method -> method.getName().equals(methodName))
                .findFirst()
                .orElseThrow();
    }

    private List<String> parameterTypeNames(Method method) {
        return Arrays.stream(method.getParameterTypes())
                .map(Class::getName)
                .toList();
    }
}
