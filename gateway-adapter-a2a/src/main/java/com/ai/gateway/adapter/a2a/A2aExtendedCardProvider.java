package com.ai.gateway.adapter.a2a;

import com.ai.gateway.application.agent.AgentCardProjection;
import com.ai.gateway.domain.model.AgentIdentity;
import com.ai.gateway.domain.model.RequestContext;

import java.util.Optional;

/**
 * 扩展卡投影的获取入口。
 *
 * <p>返回的是<b>已完成的投影</b>而不是「投影所需的上下文」，这一点是接口形态里唯一重要的决定。
 * 扩展卡要读运行面只读目录视图，而该视图受租约保护：租约一释放，其底层索引句柄随时可能被关闭。
 * 若本接口回传的是一份待执行的请求，适配层拿到它时租约早已归还，随后的投影读到的是一个
 * 可能已退休的视图——这类缺陷只在目录刚发生切换的那个瞬间显形，几乎不可能靠压测复现。
 * 把「取租约、读视图、出投影」收进实现方的一次调用里，租约的开合与读取才严格同域。</p>
 *
 * <p>实现方还需要认证与授权端口，而适配层不该持有这些端口——一旦持有，
 * 「A2A 适配器不做任何独立的授权判断」这条约束在结构上就不再成立。因此这里只声明入口，
 * 由装配层把应用层用例接进来。</p>
 *
 * <p>返回 {@link Optional#empty()} 表示投影不可用（认证失败、策略快照不健康、目录未就绪）。
 * 此时 {@link A2aServerTransportAdapter} 回退为公开卡——<b>失效关闭</b>：
 * 宁可让对端看不见任何业务域，也不能在授权结论不确定时投影出可见面。</p>
 *
 * @author cmiracle@163.com
 * @since 0.1.0
 */
@FunctionalInterface
public interface A2aExtendedCardProvider {

    /**
     * 按调用方身份投影扩展卡。
     *
     * @param identity 网关判定的对端身份，恒不为 {@code null}
     * @param context  入站请求上下文，恒不为 {@code null}
     * @return 扩展卡投影；不可用时返回 {@link Optional#empty()}
     */
    Optional<AgentCardProjection> extendedCard(AgentIdentity identity, RequestContext context);

    /**
     * 返回一个恒不可用的实现。
     *
     * <p>用于「启用了 A2A 入站但目录或授权基础设施尚未接入」的部署：所有扩展卡请求都回退为
     * 公开卡，这正是期望的初始状态。</p>
     *
     * @return 恒返回空的实现
     */
    static A2aExtendedCardProvider unavailable() {
        return (identity, context) -> Optional.empty();
    }
}
