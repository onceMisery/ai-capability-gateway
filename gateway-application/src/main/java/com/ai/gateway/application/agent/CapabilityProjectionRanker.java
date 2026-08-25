package com.ai.gateway.application.agent;

import com.ai.gateway.domain.model.CapabilityManifest;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 直投工具集的排序策略：在超出投影预算时决定「先投哪些能力」。
 *
 * <p>直投模式下 {@code tools/list} 的容量是有限的（见
 * {@link AgentToolProjectionUseCase.ProjectionBudget}）。一旦已授权能力数或投影字节数超限，
 * 就必须裁剪；裁剪顺序直接决定客户端能看到哪些工具，因此把它抽成独立策略而非写死在用例里：
 * 未来接入调用热度、业务权重或人工置顶时，只需新增实现并在装配处替换，
 * 不必修改 {@link AgentToolProjectionUseCase}（开闭原则）。</p>
 *
 * <p><b>实现必须是确定性的</b>：同一入参必须产出同一顺序。这不是风格要求而是正确性要求——
 * alias 的生成依赖排序结果（碰撞时按已分配集合延长摘要），
 * {@code tools/list} 与 {@code tools/call} 的 alias 反查各自独立地重放这一顺序，
 * 顺序不稳定会让客户端持有的 alias 指向另一个能力或直接查不到。</p>
 *
 * <p>实现还必须是无状态、线程安全的：同一实例会被并发的多个 MCP 会话共享。</p>
 *
 * @author cmiracle@163.com
 * @since 0.1.0
 */
public interface CapabilityProjectionRanker {

    /**
     * 对已通过授权与直投资格筛选的能力清单排序，靠前者优先进入 {@code tools/list}。
     *
     * @param eligible 已授权且具备直投资格的能力清单，不为 {@code null}，实现不得修改该入参
     * @return 排序后的新列表，元素与入参一致（不得增删）
     */
    List<CapabilityManifest> rank(List<CapabilityManifest> eligible);

    /**
     * 返回默认策略：按 {@code capabilityId}、再按 {@code version} 字典序升序。
     *
     * <p>之所以不默认按「检索热度」排序：当前 {@code TelemetryPort} 是只写端口，
     * 系统内并不存在可查询的调用热度数据源。用一个无法计算的指标做默认值，
     * 只会让排序退化为不可预测的顺序——而不确定的顺序会破坏 alias 稳定性。
     * 因此默认实现选择一个可验证的确定性顺序，热度排序留给后续独立实现接入。</p>
     *
     * @return 字典序排序策略，线程安全
     */
    static CapabilityProjectionRanker lexicographic() {
        return LexicographicRanker.INSTANCE;
    }

    /** 按能力标识与版本字典序排序的默认实现。 */
    final class LexicographicRanker implements CapabilityProjectionRanker {

        private static final LexicographicRanker INSTANCE = new LexicographicRanker();

        private static final Comparator<CapabilityManifest> ORDER =
                Comparator.comparing((CapabilityManifest manifest) -> manifest.metadata().id())
                        .thenComparing(manifest -> manifest.metadata().version());

        private LexicographicRanker() {
        }

        @Override
        public List<CapabilityManifest> rank(List<CapabilityManifest> eligible) {
            Objects.requireNonNull(eligible, "eligible must not be null");
            return eligible.stream().filter(Objects::nonNull).sorted(ORDER).toList();
        }
    }
}
