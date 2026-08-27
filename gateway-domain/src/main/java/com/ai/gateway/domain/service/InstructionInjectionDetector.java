package com.ai.gateway.domain.service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 指令注入检测器：判定一段不可信自然语言是否试图对模型下达指令。
 *
 * <p>网关有两个位置需要完全相同的判定，且判定必须一致：</p>
 * <ol>
 * <li><b>出站方向</b>——能力清单里由业务 Owner 手写的 {@code displayName} / {@code description} /
 * {@code examples} / Schema 叙述字段，在投影给模型或对端之前必须检测；</li>
 * <li><b>入站方向</b>——A2A 入站 Task 的文本内容，在进入候选检索之前必须检测。</li>
 * </ol>
 *
 * <p>两处若各自维护一份模式列表，就会出现「出站拦得住、入站拦不住」这类偏差，
 * 而这种偏差恰恰无法通过任何单侧测试发现。因此模式列表在本领域服务里只存在一份，
 * 出站投影与入站适配器都委派到这里。</p>
 *
 * <p><b>开闭原则</b>：内置模式是治理基线，不提供任何移除入口；企业若需追加自有模式，
 * 使用 {@link #withAdditionalPatterns(Collection)} 得到一个「内置 + 追加」的新实例，
 * 无需修改本类。判定本身对所有实例保持同一套语义（NFKC 归一化后按正则查找）。</p>
 *
 * <p>本类不可变且线程安全：{@link Pattern} 本身线程安全，模式列表构造后即冻结。</p>
 *
 * @author cmiracle@163.com
 * @since 0.1.0
 */
public final class InstructionInjectionDetector {

    /**
     * 内置治理基线模式。
     *
     * <p>覆盖四类常见注入意图：覆盖既有指令、诱导泄漏提示词或凭据（中英各一组），
     * 以及伪装成系统约束的「模型必须调用……」句式。所有模式都在 NFKC 归一化后匹配，
     * 因此全角字符、兼容字形与常见同形替换不能绕过。</p>
     */
    private static final List<Pattern> BUILT_IN_PATTERNS = List.of(
            Pattern.compile("(?iu)(ignore|disregard|override).{0,40}(instruction|prompt|system|developer)"),
            Pattern.compile("(?iu)(reveal|exfiltrate|print).{0,40}(prompt|secret|token|credential)"),
            Pattern.compile("(?u)忽略.{0,20}(指令|提示|系统|开发者)"),
            Pattern.compile("(?u)(泄露|输出|展示).{0,20}(提示词|密钥|令牌|凭据)"),
            Pattern.compile("(?iu)(assistant|model|模型).{0,30}(must|should|必须|应该).{0,30}(call|invoke|调用|执行)"));

    /** 仅含内置模式的共享实例：无状态，可安全共享。 */
    private static final InstructionInjectionDetector BUILT_IN =
            new InstructionInjectionDetector(BUILT_IN_PATTERNS);

    private final List<Pattern> patterns;

    private InstructionInjectionDetector(List<Pattern> patterns) {
        this.patterns = patterns;
    }

    /**
     * 返回仅含内置治理基线的检测器。
     *
     * @return 共享的内置检测器，永不为 {@code null}
     */
    public static InstructionInjectionDetector builtIn() {
        return BUILT_IN;
    }

    /**
     * 返回「内置模式 + 追加模式」的新检测器。
     *
     * <p>追加模式只能扩大拦截范围，不能缩小：内置模式始终在列表中，
     * 因此任何配置错误都不会把已有拦截能力配没了（失效关闭）。</p>
     *
     * @param additionalPatterns 追加的正则模式，{@code null} 或空视为不追加；元素中的
     *                           {@code null} 被忽略
     * @return 新的检测器实例
     */
    public static InstructionInjectionDetector withAdditionalPatterns(
            Collection<Pattern> additionalPatterns) {
        if (additionalPatterns == null || additionalPatterns.isEmpty()) {
            return BUILT_IN;
        }
        List<Pattern> combined = new ArrayList<>(BUILT_IN_PATTERNS);
        for (Pattern pattern : additionalPatterns) {
            if (pattern != null) {
                combined.add(pattern);
            }
        }
        return new InstructionInjectionDetector(List.copyOf(combined));
    }

    /**
     * 判定单段文本是否命中注入模式。
     *
     * @param text 待检测文本，允许为 {@code null} 或空白
     * @return 命中任一模式时返回 {@code true}；{@code null}、空白文本返回 {@code false}
     */
    public boolean detects(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC);
        for (Pattern pattern : patterns) {
            if (pattern.matcher(normalized).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判定一组文本中是否存在命中注入模式的元素。
     *
     * <p>命中即返回，不区分是哪一条命中：调用方的处置动作是整体性的
     * （整域剔除 / 整个 Task 拒绝），不需要定位到具体条目，也不应把定位结果回传给对端。</p>
     *
     * @param texts 待检测文本集合，允许为 {@code null}；元素允许为 {@code null}
     * @return 存在命中时返回 {@code true}
     */
    public boolean detectsAny(Collection<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return false;
        }
        for (String text : texts) {
            if (detects(text)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 返回当前检测器持有的模式数量。
     *
     * <p>仅用于配置自检与诊断（确认追加模式已生效），不暴露模式内容本身：
     * 模式内容一旦对外可见就等于把绕过条件也一并公开了。</p>
     *
     * @return 模式数量，恒不小于内置模式数量
     */
    public int patternCount() {
        return patterns.size();
    }

    /**
     * 返回内置治理基线的模式数量。
     *
     * @return 内置模式数量
     */
    public static int builtInPatternCount() {
        return BUILT_IN_PATTERNS.size();
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof InstructionInjectionDetector other
                && Objects.equals(patterns, other.patterns);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(patterns);
    }
}
