package com.ai.gateway.bootstrap.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证 Payload 预算配置能够被强类型绑定。 */
class PayloadLimitsPropertiesTest {

    @Test
    void bindsConfiguredPayloadBudgets() {
        PayloadLimitsProperties properties = new Binder(new MapConfigurationPropertySource(Map.of(
                "gateway.max-json-depth", "20",
                "gateway.max-array-length", "2000",
                "gateway.max-object-fields", "1500",
                "gateway.max-string-bytes", "32768",
                "gateway.max-node-count", "20000")))
                .bind("gateway", Bindable.of(PayloadLimitsProperties.class))
                .orElseThrow(() -> new AssertionError("payload limits binding missing"));

        assertThat(properties.getMaxJsonDepth()).isEqualTo(20);
        assertThat(properties.getMaxArrayLength()).isEqualTo(2000);
        assertThat(properties.getMaxObjectFields()).isEqualTo(1500);
        assertThat(properties.getMaxStringBytes()).isEqualTo(32768);
        assertThat(properties.getMaxNodeCount()).isEqualTo(20000);
    }
}
