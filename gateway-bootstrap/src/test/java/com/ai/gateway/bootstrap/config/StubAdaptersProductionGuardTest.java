package com.ai.gateway.bootstrap.config;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StubAdaptersProductionGuardTest {

    @Test
    void productionEnvironmentRejectsInlineStubs() throws Exception {
        var method = StubAdaptersConfiguration.class.getDeclaredMethod(
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

    @Test
    void developmentEnvironmentAllowsInlineStubs() throws Exception {
        var method = StubAdaptersConfiguration.class.getDeclaredMethod(
                "assertStubAllowed", String.class);
        method.setAccessible(true);

        assertThatCode(() -> {
            try {
                method.invoke(null, "development");
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }).doesNotThrowAnyException();
    }
}
