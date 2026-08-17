package com.ai.gateway.adapter.web;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Strongly typed Web adapter view of the shared gateway configuration. */
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
