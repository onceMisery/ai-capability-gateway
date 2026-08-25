package com.ai.gateway.domain.port;

import java.util.List;

/**
 * 在加密前对绑定后的调用参数进行编码，并在解密后对其进行解码。
 */
public interface ArgumentPayloadCodec {

    String encode(List<Object> arguments);

    List<Object> decode(String payload);
}
