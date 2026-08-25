package com.ai.gateway.domain.port;

import com.ai.gateway.domain.model.ErrorCode;
import com.ai.gateway.domain.model.Principal;
import com.ai.gateway.domain.model.RequestContext;

/**
 * 用于鉴权调用方并构造内部 {@link Principal} 身份的端口。
 *
 * <p>定义两个鉴权入口，二者都产出相同的内部 Principal 结构：</p>
 * <ol>
 * <li><b>{@link #authenticate(RequestContext)}</b>：从完整请求上下文（请求头、Cookie、
 * 查询参数）解析调用方身份。实现可自由从 {@code Authorization} 请求头、会话 Cookie 或
 * 查询参数中读取 Bearer 令牌。</li>
 * <li><b>{@link #validateToken(String)}</b>：校验原始令牌字符串，用于令牌已被提取并
 * 显式转发的跨服务调用场景。</li>
 * </ol>
 *
 * <p>目标模式为 JWT/OIDC：网关校验签名、签发者、受众、过期时间与必需声明。过渡模式为
 * 企业 SSO 服务端校验，通过 SSO 系统的内省端点完成；不引入任何 SSO SDK JAR，仅允许
 * 网络调用。</p>
 *
 * <p>客户端自声明的身份请求头不构成鉴权结果。{@code orgId} 是用户在会话期间选择的
 * 组织上下文，而非凭据的固有声明。除非令牌已包含由身份系统签名的 org 声明，否则网关
 * 必须在写入 Principal 之前校验该用户在该组织中的成员关系。</p>
 *
 * <p>请求体、查询参数或自定义请求头中携带的 {@code orgId}、{@code tenantId} 或
 * {@code userId} 绝不覆盖 Principal。写操作可声明 {@code maxAuthAgeSeconds}、
 * {@code requiredAcr} 与 {@code requiredAmr}；Confirm 必须重新检查鉴权新鲜度与 MFA 等级。</p>
 *
 * <p>实现此端口的适配器负责 JWT 校验或 SSO 内省（如 Sa-Token、Spring Security OAuth2、
 * CAS 或自定义 SSO）。该端口是纯粹的领域抽象，不依赖任何框架。</p>
 *
 * @see Principal
 * @see RequestContext
 * @since 0.1.0
 */
public interface AuthenticationPort {

    /**
     * 通过从请求上下文解析身份来鉴权调用方。
     *
     * <p>实现从上下文中提取凭据（Bearer 令牌、会话 Cookie 或 SSO 票据）并校验。在
     * JWT/OIDC 目标模式下，网关校验签名、签发者、受众、过期时间与必需声明。鉴权失败
     * 会写入独立的安全审计流；若该流不可用，入口点采用 Fail Closed（失效关闭）。</p>
     *
     * @param context 携带请求头、Cookie 与查询参数的请求上下文；永不为 {@code null}
     * @return 已鉴权的主体；永不为 {@code null}
     * @throws {@link ErrorCode#AUTHENTICATION_FAILED}
     * 当不存在有效凭据或校验失败时
     */
    Principal authenticate(RequestContext context);

    /**
     * 校验原始令牌并解析调用方身份。
     *
     * <p>用于令牌已被从入站请求中提取并显式转发的跨服务调用场景。令牌解析后，其
     * 行为与 {@link #authenticate(RequestContext)} 一致。</p>
     *
     * @param token Bearer 令牌（JWT 或 OIDC ID 令牌）；永不为 {@code null}
     * @return 已鉴权的主体；永不为 {@code null}
     * @throws  {@link ErrorCode#AUTHENTICATION_FAILED}
     * 当令牌无效、过期或校验失败时
     */
    Principal validateToken(String token);
}
