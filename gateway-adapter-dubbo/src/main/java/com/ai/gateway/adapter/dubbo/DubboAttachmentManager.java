package com.ai.gateway.adapter.dubbo;

import com.ai.gateway.domain.model.AttachmentWhitelist;
import com.ai.gateway.domain.model.SystemContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Dubbo 调用上下文的附件白名单管理器。
 *
 * <p>允许的附件使用 {@link AttachmentWhitelist} 中定义的平台白名单：</p>
 * <ul>
 * <li>{@code traceId} - 分布式链路追踪标识</li>
 * <li>{@code deadline} - 请求截止时间，用于向下游传播超时</li>
 * <li>{@code locale} - 请求语言区域</li>
 * <li>{@code delegatedToken} - 短期、受受众约束的委派令牌</li>
 * <li>{@code b3-traceid} - B3 链路追踪传播请求头</li>
 * <li>{@code b3-spanid} - B3 链路追踪传播请求头</li>
 * <li>{@code rtid} - 仅用于日志记录的追踪用户标识键；
 * 绝不参与授权决策</li>
 * </ul>
 *
 * <p>Manifest 不得定义任意的附件名称。未经签名的租户、用户或权限附件不参与授权。
 * 该管理器不会引入内部 Dubbo Filter 生态：不依赖内部 filter JAR、内部附件键，
 * 也不依赖隐式的调用链契约。</p>
 *
 * @author cmiracle@163.com
 * @since 0.1.0
 */
@Component
public class DubboAttachmentManager {

    private static final Logger log = LoggerFactory.getLogger(DubboAttachmentManager.class);

    /**
     * 分布式链路追踪标识的附件键。
     */
    private static final String ATTACHMENT_TRACE_ID = "traceId";

    /**
     * 请求截止时间的附件键。
     */
    private static final String ATTACHMENT_DEADLINE = "deadline";

    /**
     * 请求语言区域的附件键。
     */
    private static final String ATTACHMENT_LOCALE = "locale";

    /**
     * 委派令牌的附件键。
     */
    private static final String ATTACHMENT_DELEGATED_TOKEN = "delegatedToken";

    /**
     * B3 链路追踪 ID 的附件键。
     */
    private static final String ATTACHMENT_B3_TRACEID = "b3-traceid";

    /**
     * B3 链路追踪 span ID 的附件键。
     */
    private static final String ATTACHMENT_B3_SPANID = "b3-spanid";

    /**
     * 追踪用户标识的附件键（仅用于日志记录）。
     */
    private static final String ATTACHMENT_RTID = "rtid";

    /**
     * 构造一个新的 DubboAttachmentManager。
     */
    public DubboAttachmentManager() {
        log.info("DubboAttachmentManager initialized");
    }

    /**
     * 根据系统上下文构建 Dubbo 附件 Map，并按平台附件白名单进行过滤。
     *
     * <p>只包含白名单内的附件键。值从 {@link SystemContext} 派生：</p>
     * <ul>
     * <li>{@code traceId} ← {@code systemContext.traceId()}</li>
     * <li>{@code deadline} ← {@code String.valueOf(systemContext.deadlineEpochMs())}</li>
     * <li>{@code locale} ← {@code systemContext.locale()}</li>
     * <li>{@code b3-traceid} ← {@code systemContext.traceId()}（B3 传播）</li>
     * <li>{@code b3-spanid} ← 基于 UUID 生成的新 span ID</li>
     * <li>{@code rtid} ← {@code systemContext.traceId()}（仅用于日志记录）</li>
     * </ul>
     *
     * <p>{@code delegatedToken} 不会由该管理器从 {@link SystemContext} 设置；它需要
     * 认证上下文，而认证上下文在构建附件阶段尚不可用。</p>
     *
     * @param systemContext 平台执行上下文
     * @param whitelist 附件白名单（强制约束封闭集合）
     * @return 白名单附件键到字符串值的 Map；永不为 null
     * @throws NullPointerException 如果 systemContext 或 whitelist 为 null
     */
    public Map<String, String> buildAttachments(SystemContext systemContext,
                                                AttachmentWhitelist whitelist) {
        Objects.requireNonNull(systemContext, "systemContext must not be null");
        Objects.requireNonNull(whitelist, "whitelist must not be null");

        Set<String> allowedKeys = AttachmentWhitelist.allowedKeys();
        Map<String, String> attachments = new HashMap<>();

        // traceId（链路追踪 ID）
        if (allowedKeys.contains(ATTACHMENT_TRACE_ID)) {
            attachments.put(ATTACHMENT_TRACE_ID, systemContext.traceId());
        }

        // deadline（请求截止时间）
        if (allowedKeys.contains(ATTACHMENT_DEADLINE)) {
            attachments.put(ATTACHMENT_DEADLINE,
                    String.valueOf(systemContext.deadlineEpochMs()));
        }

        // locale（请求语言区域）
        if (allowedKeys.contains(ATTACHMENT_LOCALE)) {
            attachments.put(ATTACHMENT_LOCALE, systemContext.locale());
        }

        // b3-traceid（B3 链路传播——与 traceId 相同）
        if (allowedKeys.contains(ATTACHMENT_B3_TRACEID)) {
            attachments.put(ATTACHMENT_B3_TRACEID, systemContext.traceId());
        }

        // b3-spanid（为本跳生成新的 span ID）
        if (allowedKeys.contains(ATTACHMENT_B3_SPANID)) {
            String spanId = generateSpanId();
            attachments.put(ATTACHMENT_B3_SPANID, spanId);
        }

        // rtid（仅用于日志记录的追踪用户标识——绝不参与授权决策）
        if (allowedKeys.contains(ATTACHMENT_RTID)) {
            attachments.put(ATTACHMENT_RTID, systemContext.traceId());
        }

        // delegatedToken —— 无法从 SystemContext 获取；需要认证上下文。
        // 此处保持不设置。
        // 如果通过绑定配置了委派令牌，将由参数绑定器单独注入。

        log.debug("Built {} attachments from system context", attachments.size());
        return attachments;
    }

    /**
     * 为 B3 链路追踪传播生成新的 span ID。
     *
     * <p>使用截短的 UUID（前 16 个十六进制字符）为本次调用跳创建一个唯一的
     * span 标识。</p>
     *
     * @return 新的 span ID 字符串
     */
    private String generateSpanId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
