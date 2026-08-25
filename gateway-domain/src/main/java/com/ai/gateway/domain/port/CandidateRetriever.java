package com.ai.gateway.domain.port;

import com.ai.gateway.domain.model.CapabilityManifest;

import java.util.List;

/**
 * 自然语言路由期间基于 BM25 的候选检索端口。
 *
 * <p>（候选检索）规定 BM25 索引包含：</p>
 * <ul>
 * <li>{@code displayName} — 面向用户的能力名称。</li>
 * <li>业务动作描述。</li>
 * <li>正向、负向与同义词示例。</li>
 * <li>领域与受控标签。</li>
 * <li>公开字段名与业务描述。</li>
 * </ul>
 *
 * <p>物理索引不得包含协议地址、内部接口细节或密钥。它可包含环境中所有已发布能力。每次
 * 检索都必须在检索引擎内应用不可绕过的鉴权过滤，使当前 Principal 未被授权的能力不参与
 * 打分与 Top-K 截断。若引擎无法在查询时安全过滤，必须先构建已授权子集再检索——网关
 * 不得先取全局 Top-K 再做交集。</p>
 *
 * <p>中文检索必须使用固定的分词器、词典与同义词版本，并记录在快照与评估报告中，以避免
 * 跨实例、或发布前后出现无法解释的路由差异。</p>
 *
 * <p>初始版本使用词法检索，以免将向量数据库作为上线先决条件。只有在离线评估证明在可接受的
 * 数据治理、成本与失败模式下 Recall@K 有显著提升后，才引入向量或混合检索。</p>
 *
 * <p>实现此端口的适配器构建并查询 BM25 索引。该端口是纯粹的领域抽象，不依赖任何框架。</p>
 *
 * @see CapabilityManifest
 * @see ScoredCapability
 * @since 0.1.0
 */
public interface CandidateRetriever {

    /** 返回支撑当前索引的目录版本，未知时为 -1。 */
    default long indexedCatalogVersion() {
        return -1L;
    }

    /**
     * 从已授权的能力集合中检索与归一化用户文本匹配、按分数排序的 Top-K 能力。
     *
     * <p>规定：鉴权过滤不可绕过。当前 Principal 未被授权的能力不参与打分或 Top-K 截断。
     * {@code authorizedCapabilities} 参数是预先过滤后的集合。</p>
     *
     * <p>规定：网关在检索后应用阈值检查——最小相关度分数、Top-1 与 Top-2 最小分差，以及
     * 最大候选数。检索器只返回已打分候选；最终路由决策由网关做出。</p>
     *
     * @param normalizedText 归一化后的用户自然语言文本
     * @param authorizedCapabilities 在其内部搜索的预授权能力集合
     * @param topK 返回的最大候选数
     * @return 按分数降序排列的已打分能力列表；永不为 {@code null}
     */
    List<ScoredCapability> retrieve(String normalizedText,
                                    List<CapabilityManifest> authorizedCapabilities,
                                    int topK);

    /**
     * 携带其 BM25 相关度分数的能力。
     *
     * <p>规定：该分数是检索引擎计算的 BM25 相关度分数。网关不得依赖模型自报的置信度，
     * 而是使用离线标注集合确定的阈值。</p>
     *
     * @param capability 匹配到的能力清单
     * @param score BM25 相关度分数
     */
    record ScoredCapability(CapabilityManifest capability, double score) {
    }
}
