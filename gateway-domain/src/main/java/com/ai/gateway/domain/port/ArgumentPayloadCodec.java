package com.ai.gateway.domain.port;

import java.util.List;

/**
 * Encodes bound invocation arguments before encryption and decodes them after decryption.
 */
public interface ArgumentPayloadCodec {

    String encode(List<Object> arguments);

    List<Object> decode(String payload);
}
