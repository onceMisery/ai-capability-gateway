package com.ai.gateway.adapter.web;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayWebPropertiesTest {

    @Test
    void bindsRequestLimitAndRateLimitProviderFromGatewayKeys() {
        GatewayWebProperties properties = new Binder(new MapConfigurationPropertySource(Map.of(
                "gateway.max-request-size-bytes", "131072",
                "gateway.ratelimit.provider", "sentinel")))
                .bind("gateway", Bindable.of(GatewayWebProperties.class))
                .orElseThrow(() -> new IllegalStateException("gateway web properties not bound"));

        assertThat(properties.getMaxRequestSizeBytes()).isEqualTo(131072);
        assertThat(properties.getRatelimit().getProvider()).isEqualTo("sentinel");
    }
}
