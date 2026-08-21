package com.ai.gateway.bootstrap.config;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * StubAuthProductionGuardTest 类。
 *
 * @author cmiracle@163.com
 */
class StubAuthProductionGuardTest {

    @Test
    void productionEnvironmentRejectsStubAuthentication() throws Exception {
        var method = StubAuthConfiguration.class.getDeclaredMethod(
                "assertStubAllowed", String.class);
        method.setAccessible(true);

        assertThatThrownBy(() -> {
            try {
                method.invoke(null, "production");
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stub");
    }
}
