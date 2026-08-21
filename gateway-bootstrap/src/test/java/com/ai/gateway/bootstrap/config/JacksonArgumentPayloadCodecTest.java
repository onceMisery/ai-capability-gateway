package com.ai.gateway.bootstrap.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JacksonArgumentPayloadCodecTest 类。
 *
 * @author cmiracle@163.com
 */
class JacksonArgumentPayloadCodecTest {

    @Test
    void roundTripsJsonCompatibleArguments() {
        JacksonArgumentPayloadCodec codec = new JacksonArgumentPayloadCodec(new ObjectMapper());

        String payload = codec.encode(List.of("order-1", 7, Map.of("enabled", true)));

        assertThat(codec.decode(payload))
                .containsExactly("order-1", 7, Map.of("enabled", true));
    }
}
