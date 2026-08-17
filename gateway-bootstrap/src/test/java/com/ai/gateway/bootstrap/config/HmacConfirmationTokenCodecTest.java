package com.ai.gateway.bootstrap.config;

import com.ai.gateway.domain.model.ConfirmationToken;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HmacConfirmationTokenCodecTest {

    private final HmacConfirmationTokenCodec codec = new HmacConfirmationTokenCodec(properties(
            "0123456789abcdef0123456789abcdef", "test"));

    @Test
    void roundTripsBoundClaims() {
        Instant expiresAt = Instant.ofEpochSecond(1_900_000_000L);

        ConfirmationToken issued = codec.issue("op-1", "principal", 7L, "arguments", expiresAt);
        ConfirmationToken verified = codec.verify(issued.token());

        assertThat(verified.operationId()).isEqualTo("op-1");
        assertThat(verified.principalDigest()).isEqualTo("principal");
        assertThat(verified.orgId()).isEqualTo(7L);
        assertThat(verified.argumentsDigest()).isEqualTo("arguments");
        assertThat(verified.expiresAt()).isEqualTo(expiresAt);
    }

    @Test
    void rejectsTamperedPayload() {
        ConfirmationToken issued = codec.issue(
                "op-1", "principal", 7L, "arguments", Instant.now().plusSeconds(60));
        String tampered = issued.token().replaceFirst("^.", "x");

        assertThatThrownBy(() -> codec.verify(tampered))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void productionRequiresConfiguredSecret() {
        assertThatThrownBy(() -> new HmacConfirmationTokenCodec(properties("", "production")))
                .isInstanceOf(IllegalStateException.class);
    }

    private static GatewayProperties properties(String secret, String environment) {
        GatewayProperties properties = new GatewayProperties();
        properties.setEnvironment(environment);
        properties.getOperation().setConfirmationSecret(secret);
        return properties;
    }
}
