package com.ai.gateway.domain.model;

import java.util.Set;
import java.util.Collections;

/**
 * 平台允许的 Dubbo attachment（或等价协议上下文）键白名单。
 *
 * <p>定义清单可通过 {@link AttachmentBinding} 绑定的封闭附件键集合。清单不得定义
 * 任意附件名。未签名的租户、用户或权限附件不参与鉴权。</p>
 *
 * <p>白名单键如下：</p>
 * <ul>
 * <li>{@code traceId} - 分布式追踪标识。</li>
 * <li>{@code deadline} - 用于下游超时传播的请求截止时间。</li>
 * <li>{@code locale} - 请求语言区域。</li>
 * <li>{@code delegatedToken} - 由 Provider 校验的短时效、面向特定受众的委托令牌。</li>
 * <li>{@code b3-traceid} - B3 追踪传播头。</li>
 * <li>{@code b3-spanid} - B3 跨度传播头。</li>
 * <li>{@code rtid} - 仅用于日志的追踪用户标识键，绝不参与鉴权。</li>
 * </ul>
 *
 * <p>若 Provider 使用任何未签名键来确定业务身份或租户，该接口视为不合规。</p>
 *
 * @since 0.1.0
 */
public record AttachmentWhitelist() {

    /**
     * 允许附件键的不可变集合。
     */
    private static final Set<String> ALLOWED_KEYS = Set.of(
            "traceId",
            "deadline",
            "locale",
            "delegatedToken",
            "b3-traceid",
            "b3-spanid",
            "rtid"
    );

    /**
     * 返回允许附件键的不可修改视图。
     *
     * @return 白名单附件键名集合
     */
    public static Set<String> allowedKeys() {
        return Collections.unmodifiableSet(ALLOWED_KEYS);
    }

    /**
     * 返回给定键是否位于附件白名单中。
     *
     * @param key 待检查的附件键
     * @return 该键在白名单中时为 {@code true}
     */
    public static boolean isAllowed(String key) {
        return ALLOWED_KEYS.contains(key);
    }
}
