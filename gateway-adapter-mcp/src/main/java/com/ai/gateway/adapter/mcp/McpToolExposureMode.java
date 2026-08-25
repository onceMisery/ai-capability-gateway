package com.ai.gateway.adapter.mcp;

/**
 * {@code tools/list} 的工具曝光模式。
 *
 * <p>网关最初只暴露两个 Meta-Tool（{@code gateway_resolve} / {@code gateway_call}），
 * 动机是「不把能力目录镜像进 {@code tools/list}」。该动机成立，但代价是通用 MCP 客户端
 * 拿不到真实 Schema——只能看到 {@code arguments: {"type":"object"}}，模型只能盲填参数。
 * 本枚举把「曝光形态」显式化为一个可配置维度，让两种客户端各取所需：</p>
 *
 * <ul>
 * <li>{@link #META_TOOL}：保持原有两工具面。适用于已适配的受信 Host，以及能力数量
 * 大到不适合直投的部署；</li>
 * <li>{@link #DIRECT_PROJECTION}：把当前会话身份已授权的只读能力直接投影为工具，
 * 工具名用 {@code cap_<hash>} alias。适用于 Claude Code、IDE Agent 等通用客户端；</li>
 * <li>{@link #HYBRID}：直投 + 保留 Meta-Tool 兜底。推荐默认值。</li>
 * </ul>
 *
 * <p><b>直投不放松任何安全边界。</b>它只是把三层既有保护接到协议上：可信字段剥离
 * （{@code stripTrustedFields}）、注入检测（{@code containsUnsafeContent}）、
 * 体积上限（16KB / 32 属性）。授权前置过滤、执行期重新鉴权、alias 与 {@code toolRef}
 * 的解耦一个都没有变化——隐藏 Schema 从来不是安全机制，它只是把成本转移给模型。</p>
 *
 * <p>模式只决定「展示什么」，不决定「能执行什么」：即使工具没进 {@code tools/list}，
 * 只要仍被授权，就仍可通过 Meta-Tool 路径调用；反之持有 alias 也不等于持有执行许可。</p>
 *
 * @author cmiracle@163.com
 * @since 0.1.0
 */
public enum McpToolExposureMode {

    /** 仅暴露 {@code gateway_resolve} 与 {@code gateway_call}（历史行为，保留为兜底）。 */
    META_TOOL,

    /** 仅暴露按会话身份投影的已授权只读能力；超预算时自动补上 Meta-Tool 兜底。 */
    DIRECT_PROJECTION,

    /** 直投能力与 Meta-Tool 并存，生产推荐值。 */
    HYBRID;

    /**
     * 是否需要执行能力投影。
     *
     * @return {@code META_TOOL} 之外的模式均为 {@code true}
     */
    public boolean projectsCapabilities() {
        return this != META_TOOL;
    }

    /**
     * 在投影成功且未降级的情况下，是否仍然保留 Meta-Tool。
     *
     * <p>{@code DIRECT_PROJECTION} 返回 {@code false}：它的语义是「只给通用客户端看真实
     * 工具」。但这只在「投影完整」时成立——一旦裁剪或投影失败，实现会无条件补回
     * Meta-Tool，否则被裁掉的能力将没有任何可达路径（见
     * {@link McpProjectedToolCatalog}）。</p>
     *
     * @return {@code META_TOOL} 与 {@code HYBRID} 为 {@code true}
     */
    public boolean retainsMetaTools() {
        return this != DIRECT_PROJECTION;
    }
}
