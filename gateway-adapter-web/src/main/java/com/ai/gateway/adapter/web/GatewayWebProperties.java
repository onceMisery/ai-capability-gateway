package com.ai.gateway.adapter.web;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Web 适配层对共享网关配置的类型化视图。
 *
 * <p>从共享的 {@code gateway.*} 配置树中读取 Web 适配层所需的少量配置项，
 * 与 {@code gateway-domain}、{@code gateway-application} 的配置解耦。</p>
 *
 * @author cmiracle@163.com
 * @since 0.1.0
 */
@ConfigurationProperties(prefix = "gateway")
public class GatewayWebProperties {

    private int maxRequestSizeBytes = 65_536;
    private Provider ratelimit = new Provider();

    public int getMaxRequestSizeBytes() {
        return maxRequestSizeBytes;
    }

    public void setMaxRequestSizeBytes(int maxRequestSizeBytes) {
        this.maxRequestSizeBytes = maxRequestSizeBytes;
    }

    public Provider getRatelimit() {
        return ratelimit;
    }

    public void setRatelimit(Provider ratelimit) {
        this.ratelimit = ratelimit;
    }

    public static class Provider {
        private String provider = "";

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }
    }
}
