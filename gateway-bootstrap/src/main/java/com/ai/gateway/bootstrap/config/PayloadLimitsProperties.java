package com.ai.gateway.bootstrap.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Payload 结构预算的强类型配置。
 *
 * <p>请求/响应字节上限继续由 {@link GatewayProperties} 维护；本类只维护
 * 原先散落在 YAML 和领域服务中的结构限制，避免出现第二套默认值。</p>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "gateway")
public class PayloadLimitsProperties {

    /** JSON 最大深度，根节点深度为 0。 */
    private int maxJsonDepth = 16;

    /** 单个数组或对象允许的最大成员数。 */
    private int maxArrayLength = 1_000;

    /** 单个对象允许的最大字段数。 */
    private int maxObjectFields = 1_000;

    /** 单个字符串允许的最大 UTF-8 字节数。 */
    private int maxStringBytes = 16 * 1024;

    /** 单棵 JSON 树允许的最大节点数。 */
    private long maxNodeCount = 10_000L;
}
