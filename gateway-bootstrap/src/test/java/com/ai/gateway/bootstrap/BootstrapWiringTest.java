package com.ai.gateway.bootstrap;

import com.ai.gateway.bootstrap.config.BeanConfig;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Primary;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class BootstrapWiringTest {

    @Test
    void providerInvocationUsesPrimaryResilienceDecorator() {
        var method = Arrays.stream(BeanConfig.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals("resilientInvocationAdapter"))
                .findFirst().orElseThrow();

        assertThat(method.isAnnotationPresent(Primary.class)).isTrue();
        assertThat(Arrays.stream(method.getParameterTypes())
                .anyMatch(type -> type.getSimpleName().equals("DubboInvocationAdapter"))).isTrue();
    }
}
