package com.ai.gateway.bootstrap.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JacksonConfigurationTest {

    @Test
    void shouldRejectDuplicateRequestFields() {
        var objectMapper = new JacksonConfiguration()
                .objectMapper(new Jackson2ObjectMapperBuilder());

        assertThatThrownBy(() -> objectMapper.readTree("{\"id\":1,\"id\":2}"))
                .isInstanceOf(JsonProcessingException.class)
                .hasMessageContaining("Duplicate field");
    }
}
