package com.ai.gateway.application.agent;

import com.ai.gateway.application.catalog.ActiveCatalogView;
import com.ai.gateway.application.catalog.InMemoryCatalogManager;
import com.ai.gateway.domain.model.AgentIdentity;
import com.ai.gateway.domain.model.PolicySnapshot;
import com.ai.gateway.domain.model.Principal;
import com.ai.gateway.domain.model.RequestContext;
import com.ai.gateway.domain.port.AuthenticationPort;
import com.ai.gateway.domain.port.AuthorizationPort;
import com.ai.gateway.domain.port.TelemetryPort;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 分级 Agent 卡片的查询用例：把「取得投影所需的上下文」这件事收在应用层。
 *
 * <p>存在的理由是一条生命周期约束，而不是分层洁癖。扩展卡的投影要读运行面目录视图，
 * 而该视图必须在<b>租约持有期内</b>被读取——租约一关，索引就可能被回收。因此不能由适配层
 * 先拿到一份「投影请求」再自己去调投影服务：那时租约早已释放，读到的是一个可能已退休的视图。
 * 本用例把认证、租约、策略快照与投影全部包在一次调用里，租约的开合与读取严格同域。</p>
 *
 * <p>三处不可用<b>一律回空</b>，由调用方回退到公开卡（失效关闭）：认证失败、目录未就绪、
 * 策略快照不健康。三者不作区分，因为对未通过认证的调用方而言，「网关此刻不确定你的可见面」
 * 与「你没有任何可见面」应当不可区分——任何差异都会把授权结论变成可探测信息。</p>
 *
 * <p>协议中立：本类不认识 A2A 或 MCP 的任何类型，只产出 {@link AgentCardProjection}。
 * 新增一个协议承载面时，只需在该协议的适配层加一个编码器，本用例不改（开闭原则）。</p>
 *
 * <p>本类无可变状态，线程安全。</p>
 *
 * @author cmiracle@163.com
 * @since 0.1.0
 */
public final class AgentCardQueryUseCase {

    private final AuthenticationPort authenticationPort;
    private final AuthorizationPort authorizationPort;
    private final InMemoryCatalogManager catalogManager;
    private final AgentCardProjectionService projectionService;
    private final TelemetryPort telemetry;

    /**
     * @param authenticationPort 认证端口，不能为 {@code null}
     * @param authorizationPort  授权端口，不能为 {@code null}
     * @param catalogManager     运行面目录管理器，不能为 {@code null}
     * @param projectionService  分级卡片投影服务，不能为 {@code null}
     * @param telemetry          埋点端口，不能为 {@code null}
     */
    public AgentCardQueryUseCase(AuthenticationPort authenticationPort,
                                 AuthorizationPort authorizationPort,
                                 InMemoryCatalogManager catalogManager,
                                 AgentCardProjectionService projectionService,
                                 TelemetryPort telemetry) {
        this.authenticationPort = Objects.requireNonNull(
                authenticationPort, "authenticationPort must not be null");
        this.authorizationPort = Objects.requireNonNull(
                authorizationPort, "authorizationPort must not be null");
        this.catalogManager = Objects.requireNonNull(
                catalogManager, "catalogManager must not be null");
        this.projectionService = Objects.requireNonNull(
                projectionService, "projectionService must not be null");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry must not be null");
    }

    /**
     * 返回身份无关的公开卡。
     *
     * <p>不接触目录也不接触授权：公开卡的 {@code skills} 恒为空，本就没有需要裁剪的内容。
     * 让它不依赖任何运行面状态，是为了保证「目录未就绪时公开卡仍然可用」——
     * 否则失效关闭的回退目标本身也会一起不可用。</p>
     *
     * @return 公开卡投影
     */
    public AgentCardProjection publicCard() {
        return projectionService.publicCard();
    }

    /**
     * 按调用方身份投影扩展卡。
     *
     * @param identity 网关判定的对端身份，不能为 {@code null}
     * @param context  入站请求上下文，不能为 {@code null}
     * @return 扩展卡投影；认证、目录或策略任一不可用时返回 {@link Optional#empty()}
     */
    public Optional<AgentCardProjection> extendedCard(AgentIdentity identity,
                                                     RequestContext context) {
        Objects.requireNonNull(identity, "identity must not be null");
        Objects.requireNonNull(context, "context must not be null");
        Principal principal = authenticate(context);
        if (principal == null) {
            return unavailable("authentication");
        }
        ActiveCatalogView.ViewLease lease = catalogManager.acquireActiveView();
        if (lease == null) {
            return unavailable("catalog");
        }
        try {
            ActiveCatalogView view = lease.view();
            if (view == null || view.catalogVersion() <= 0) {
                return unavailable("catalog");
            }
            PolicySnapshot policySnapshot = policySnapshot(principal);
            if (policySnapshot == null) {
                return unavailable("policy");
            }
            // 投影必须在租约内完成：租约释放后视图可能已退休，其索引随时被关闭。
            return Optional.of(projectionService.extendedCard(
                    new AgentCardProjectionService.ExtendedCardRequest(
                            identity, view, policySnapshot)));
        } finally {
            lease.close();
        }
    }

    private Optional<AgentCardProjection> unavailable(String stage) {
        telemetry.increment("gateway.agent.card.unavailable", Map.of("stage", stage));
        return Optional.empty();
    }

    /** 认证异常等同于认证失败：把基础设施抖动记成「不可用」而不是放行。 */
    private Principal authenticate(RequestContext context) {
        try {
            return authenticationPort.authenticate(context);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 解析策略快照；不健康或 epoch 非法时返回 {@code null}（失效关闭）。 */
    private PolicySnapshot policySnapshot(Principal principal) {
        PolicySnapshot policySnapshot;
        try {
            policySnapshot = authorizationPort.resolvePolicySnapshot(principal);
        } catch (RuntimeException e) {
            return null;
        }
        if (policySnapshot == null || !policySnapshot.healthy()
                || policySnapshot.policyEpoch() <= 0) {
            return null;
        }
        return policySnapshot;
    }
}
