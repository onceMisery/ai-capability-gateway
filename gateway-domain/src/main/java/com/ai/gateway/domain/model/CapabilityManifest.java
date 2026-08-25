package com.ai.gateway.domain.model;

import java.util.List;
import java.util.Map;

/**
 * 完整的、机器可校验的能力清单，将受治理的微服务 API 转化为可通过自然语言发现的能力。
 *
 * <p>定义顶层结构。清单使用 YAML 或 JSON 格式，并受带版本的 JSON Schema 校验。
 * Markdown 仅用于补充性的可读文档，并非可执行的契约。</p>
 *
 * <p>每个清单拥有稳定的 {@code metadata.id}、语义化版本与内容 SHA-256 摘要。相同的
 * {@code metadata.id + version} 内容不可被覆盖；修改必须产生新版本。</p>
 *
 * <p>清单仅包含协议类型名字符串；网关在编译或运行期都不会加载 {@code interfaceName}、
 * {@code parameterTypes} 或任何业务 API 类。</p>
 *
 * <p>生命周期状态、确认记录、发布环境与快照版本属于控制面记录，不在清单中自行声明。</p>
 *
 * @param apiVersion 清单规范版本（如 "gateway.ai/v1"）
 * @param kind 固定为 "Capability"
 * @param metadata 能力元数据（ID、版本、拥有者、标签）
 * @param spec 能力规格说明
 * @since 0.1.0
 */
public record CapabilityManifest(
        String apiVersion,
        String kind,
        Metadata metadata,
        Spec spec
) {

    /**
     * 紧凑构造器，执行 null 检查。
     *
     * @param apiVersion API 版本
     * @param kind 类型
     * @param metadata 元数据
     * @param spec 规格说明
     */
    public CapabilityManifest {
        java.util.Objects.requireNonNull(apiVersion, "apiVersion must not be null");
        java.util.Objects.requireNonNull(kind, "kind must not be null");
        java.util.Objects.requireNonNull(metadata, "metadata must not be null");
        java.util.Objects.requireNonNull(spec, "spec must not be null");
    }

    /**
     * 标识能力、其版本与责任团队的元数据块。
     *
     * <p>{@code id} 采用 {@code domain.resource.action} 约定（如
     * {@code order.detail.query}），仅允许小写字母、数字、点与连字符。一旦发布，
     * ID 不可重命名。</p>
     *
     * @param id 全局稳定的能力标识
     * @param version SemVer 版本字符串
     * @param owner 责任团队与联系方式
     * @param tags 受控标签（可选）
     */
    public record Metadata(
            String id,
            String version,
            Owner owner,
            List<String> tags
    ) {
        /**
         * 紧凑构造器，执行防御性拷贝。
         *
         * @param id 能力 ID
         * @param version 版本
         * @param owner 拥有者
         * @param tags 标签
         */
        public Metadata {
            java.util.Objects.requireNonNull(id, "id must not be null");
            java.util.Objects.requireNonNull(version, "version must not be null");
            java.util.Objects.requireNonNull(owner, "owner must not be null");
            tags = tags == null ? List.of() : List.copyOf(tags);
        }
    }

    /**
     * 能力的责任团队与联系方式。
     *
     * @param team 团队名（如 "order-platform"）
     * @param contact 联系邮箱（如 "order-platform@example.com"）
     */
    public record Owner(
            String team,
            String contact
    ) {

        /**
         * 紧凑构造器，执行 null 检查。
         *
         * @param team 团队名
         * @param contact 联系邮箱
         */
        public Owner {
            java.util.Objects.requireNonNull(team, "team must not be null");
            java.util.Objects.requireNonNull(contact, "contact must not be null");
        }
    }

    /**
     * 包含全部执行相关配置的能力规格说明。
     *
     * <p>定义以下必需字段：</p>
     * <ul>
     * <li>{@code displayName} - 面向用户的能力名称。</li>
     * <li>{@code description} - 单一业务动作描述。</li>
     * <li>{@code examples} - 正向、负向与消歧示例。</li>
     * <li>{@code risk} - 决定执行模式的风险等级。</li>
     * <li>{@code inputSchema} - 模型可见的 JSON Schema 2020-12。</li>
     * <li>{@code authorization} - 所需权限与 Principal 声明（初始版本可选）。</li>
     * <li>{@code invocation} - 协议绑定与确定性参数映射。</li>
     * <li>{@code output} - 响应解包、投影、脱敏与 Schema。</li>
     * <li>{@code resilience} - 超时、重试、并发与熔断。</li>
     * </ul>
     *
     * @param displayName 面向用户的能力名称
     * @param description 单一业务动作描述
     * @param examples 正向、负向与同义词示例
     * @param risk 风险等级
     * @param inputSchema 模型可见的 JSON Schema
     * @param authorization 所需权限与 Principal 声明
     * @param invocation 协议绑定
     * @param output 出参契约
     * @param resilience 韧性策略
     */
    public record Spec(
            String displayName,
            String description,
            Examples examples,
            RiskLevel risk,
            Map<String, Object> inputSchema,
            Authorization authorization,
            ProtocolBinding invocation,
            OutputContract output,
            ResiliencePolicy resilience
    ) {

        /**
         * 紧凑构造器，执行防御性拷贝。
         *
         * @param displayName 显示名称
         * @param description 描述
         * @param examples 示例
         * @param risk 风险等级
         * @param inputSchema 入参 Schema
         * @param authorization 鉴权配置
         * @param invocation 调用绑定
         * @param output 出参契约
         * @param resilience 韧性策略
         */
        public Spec {
            java.util.Objects.requireNonNull(displayName, "displayName must not be null");
            java.util.Objects.requireNonNull(description, "description must not be null");
            java.util.Objects.requireNonNull(examples, "examples must not be null");
            java.util.Objects.requireNonNull(risk, "risk must not be null");
            java.util.Objects.requireNonNull(inputSchema, "inputSchema must not be null");
            java.util.Objects.requireNonNull(invocation, "invocation must not be null");
            java.util.Objects.requireNonNull(output, "output must not be null");
            java.util.Objects.requireNonNull(resilience, "resilience must not be null");
            inputSchema = Map.copyOf(inputSchema);
        }
    }

    /**
     * 用于检索与模型路由的自然语言示例。
     *
     * <p>至少要求：</p>
     * <ul>
     * <li>三个正向示例。</li>
     * <li>两个负向示例，指明容易混淆的替代项。</li>
     * <li>关键名词同义词。</li>
     * </ul>
     *
     * <p>示例参与 BM25 检索与模型路由。它们是由业务 Owner 确认的受治理生产配置。</p>
     *
     * @param positive 正向示例（该能力处理的查询）
     * @param negative 负向示例（其不处理的查询）
     * @param synonyms 关键名词同义词
     */
    public record Examples(
            List<String> positive,
            List<String> negative,
            List<String> synonyms
    ) {

        /**
         * 紧凑构造器，执行防御性拷贝。
         *
         * @param positive 正向示例
         * @param negative 负向示例
         * @param synonyms 同义词
         */
        public Examples {
            java.util.Objects.requireNonNull(positive, "positive must not be null");
            java.util.Objects.requireNonNull(negative, "negative must not be null");
            java.util.Objects.requireNonNull(synonyms, "synonyms must not be null");
            positive = List.copyOf(positive);
            negative = List.copyOf(negative);
            synonyms = List.copyOf(synonyms);
        }
    }

    /**
     * 调用该能力所需的鉴权要求。
     *
     * <p>{@code permissions} 采用 {@code domain:resource:action} 三段式约定。
     * 禁止通配符。</p>
     *
     * <p>{@code principalClaims} 定义必需的 Principal 声明（如 {@code /orgId} 必须为
     * 整数且必填）。网关在参数绑定前校验这些声明。</p>
     *
     * <p>开发 stub 模式可对只读能力省略此块；生产策略加载仍须显式且失效关闭
     *（fail-closed）。</p>
     *
     * @param permissions 所需权限字符串
     * @param principalClaims 以 JSON Pointer 为键的必需 Principal 声明
     * @param maxAuthAgeSeconds 写操作鉴权新鲜度上限
     * @param requiredAcr 必需的认证上下文类引用
     * @param requiredAmr 必需的认证方法
     */
    public record Authorization(
            List<String> permissions,
            Map<String, ClaimRequirement> principalClaims,
            Integer maxAuthAgeSeconds,
            String requiredAcr,
            List<String> requiredAmr
    ) {

        /**
         * 创建不声明增强认证要求的授权配置。
         *
         * @param permissions 权限列表
         * @param principalClaims Principal 声明要求
         */
        public Authorization(
                List<String> permissions,
                Map<String, ClaimRequirement> principalClaims) {
            this(permissions, principalClaims, null, null, List.of());
        }

        /**
         * 紧凑构造器，执行防御性拷贝。
         *
         * @param permissions 权限字符串
         * @param principalClaims Principal 声明要求
         * @param maxAuthAgeSeconds 鉴权新鲜度上限
         * @param requiredAcr 必需的认证上下文
         * @param requiredAmr 必需的认证方法
         */
        public Authorization {
            java.util.Objects.requireNonNull(permissions, "permissions must not be null");
            permissions = List.copyOf(permissions);
            principalClaims = principalClaims == null ? Map.of() : Map.copyOf(principalClaims);
            requiredAmr = requiredAmr == null ? List.of() : List.copyOf(requiredAmr);
        }
    }

    /**
     * 单个 Principal 声明的要求。
     *
     * <p>定义期望的类型以及该声明是否必填。网关在参数绑定前校验 Principal 声明。</p>
     *
     * @param type 期望的声明类型（如 "integer"、"string"）
     * @param required 该声明是否必须存在且非 null
     */
    public record ClaimRequirement(
            String type,
            boolean required
    ) {

        /**
         * 紧凑构造器，执行 null 检查。
         *
         * @param type 声明类型
         * @param required 声明是否必填
         */
        public ClaimRequirement {
            java.util.Objects.requireNonNull(type, "type must not be null");
        }
    }
}
