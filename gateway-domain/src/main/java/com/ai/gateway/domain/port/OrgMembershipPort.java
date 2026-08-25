package com.ai.gateway.domain.port;

/**
 * 校验组织成员关系的端口。
 *
 * <p>规定 {@code orgId} 是用户在会话期间选择的_组织上下文，而非凭据的固有声明。除非令牌
 * 已包含由身份系统签名的 org 声明，否则网关必须在将其写入
 * {@link com.ai.gateway.domain.model.Principal} 之前校验该用户在该组织中的成员关系。</p>
 *
 * <p>未经验证的 {@code orgId} 绝不能进入 Principal，也不得用于 PRINCIPAL 参数绑定。校验
 * 结果可用较短 TTL 缓存；缓存未命中叠加数据源不可用时必须采用 Fail Closed——请求不得
 * 放行，网关也不得回退为信任客户端自报的 {@code orgId}。</p>
 *
 * <p>相较于信任客户端上报 org 请求头并静默取默认值的传统入口，这是一项关键的安全增强。
 * 任何实现都不得降级为直接信任客户端请求头。</p>
 *
 * <p>实现此端口的适配器查询用户服务或鉴权数据源。该端口是纯粹的领域抽象，不依赖任何
 * 框架。</p>
 *
 * @see com.ai.gateway.domain.model.Principal
 * @since 0.1.0
 */
public interface OrgMembershipPort {

    /**
     * 校验给定主体是否属于指定的组织。
     *
     * <p>规定：用户选择 {@code orgId} 后，网关通过鉴权数据源校验成员关系。结果可用较短 TTL
     * 缓存。在缓存未命中且数据源不可用时采用 Fail Closed——返回 {@code false}。</p>
     *
     * @param subject 已鉴权的用户标识（如 "user-123"）
     * @param orgId 用户选择的组织上下文
     * @return 若主体是组织成员则为 {@code true}；否则或在数据源失败时（Fail Closed）为
     * {@code false}
     */
    boolean verifyMembership(String subject, long orgId);
}
