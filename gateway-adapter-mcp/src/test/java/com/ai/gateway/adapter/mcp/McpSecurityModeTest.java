package com.ai.gateway.adapter.mcp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class McpSecurityModeTest {

    @Test
    void noAuthIsExplicitAndRemainsReadOnly() {
        assertThat(McpSecurityMode.parse("NO_AUTH")).isEqualTo(McpSecurityMode.NO_AUTH);
        assertThat(McpSecurityMode.NO_AUTH.allowWritePrepare()).isFalse();
        assertThat(McpSecurityMode.parse(null)).isEqualTo(McpSecurityMode.READ_ONLY);
    }
}
