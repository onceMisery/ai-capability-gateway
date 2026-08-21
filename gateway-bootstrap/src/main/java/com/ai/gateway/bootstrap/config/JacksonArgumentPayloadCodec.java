package com.ai.gateway.bootstrap.config;

import com.ai.gateway.domain.port.ArgumentPayloadCodec;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * 针对加密、按位置排序的调用参数的 JSON 编解码器。
 *
 * @author cmiracle@163.com
 */
@Component
public final class JacksonArgumentPayloadCodec implements ArgumentPayloadCodec {

    private static final TypeReference<List<Object>> ARGUMENT_LIST = new TypeReference<>() { };
    private final ObjectMapper objectMapper;

    public JacksonArgumentPayloadCodec(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    public String encode(List<Object> arguments) {
        try {
            return objectMapper.writeValueAsString(arguments);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("unable to encode operation arguments", e);
        }
    }

    @Override
    public List<Object> decode(String payload) {
        try {
            return List.copyOf(objectMapper.readValue(payload, ARGUMENT_LIST));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("unable to decode operation arguments", e);
        }
    }
}
