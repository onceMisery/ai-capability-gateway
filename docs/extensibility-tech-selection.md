# AI 能力网关 — 可扩展性组件技术选型规范

> 版本：1.0-DRAFT  
> 日期：2026-07-28  
> 状态：已评审确认；M1~M4 已实现并通过构建验证，M5 集成测试已就绪（执行需 Docker）

---

## 1. 文档目的

本文档针对 AI 能力网关（ai-capability-gateway）从原型验证走向生产部署过程中，需要补齐的**可扩展性基础设施组件**进行技术选型讨论。

选型原则：
- **可插拔优先**：所有组件通过 Domain Port（SPI 接口）接入，实现可替换
- **最小侵入**：优先引入核心 jar，避免框架级绑定
- **渐进式集成**：先跑通参考实现，再按需替换为企业级方案
- **与现有技术栈一致**：优先选择团队已有经验的组件

---

## 2. 现有架构基础

当前项目采用六边形架构，Domain 层已定义 22 个 Port 接口：

| Port 接口 | 当前状态 | 本次选型涉及 |
|-----------|---------|:---:|
| `AuthenticationPort` | Stub（固定 Principal） | ✅ |
| `AuthorizationPort` | Stub（全部放行） | ✅ |
| `RateLimiterPort` | Stub（无限制） | ✅ |
| `SnapshotNotifier` | Stub（仅日志） | ✅ |
| `CatalogPort` | JDBC 实现（PostgreSQL） | ✅ |
| `LlmRouterPort` | HTTP 实现（DeepSeek） | 后续 |
| `InvocationAdapter` | Dubbo 泛化调用 | 已完成 |
| `SecretManager` | Stub（明文） | 后续 |

---

## 3. 组件一：权限验证模块（可插拔认证授权）

### 3.1 需求背景

原设计将权限验证完全留给接入方（业务系统）处理，网关仅透传 Token。生产环境需要：
- 网关自身具备**认证能力**（验证调用者身份）
- 支持**授权决策**（判断调用者是否有权访问某能力）
- 架构上**不绑定任何具体认证框架**，接入方可自由选择

### 3.2 接口设计

```java
// ===== gateway-domain 层（纯接口，零依赖）=====

/**
 * 认证端口：从请求上下文中解析调用者身份。
 * 实现方可以是 Sa-Token、Spring Security OAuth2、CAS、自研 SSO 等。
 */
public interface AuthenticationPort {

    /**
     * 从 HTTP 请求中解析 Principal。
     * @param context 请求上下文（headers、cookies、query params 等）
     * @return 认证后的 Principal，认证失败抛出 AuthenticationException
     */
    Principal authenticate(RequestContext context);

    /**
     * 验证 Token 有效性（用于跨服务调用场景）。
     */
    Principal validateToken(String token);
}

/**
 * 授权端口：判断已认证的 Principal 是否有权执行某操作。
 * 实现方可以是 RBAC、ABAC、ReBAC、自定义权限系统等。
 */
public interface AuthorizationPort {

    /**
     * 能力可见性过滤：返回该 Principal 可见的能力列表。
     */
    List<CapabilityManifest> filterVisibleCapabilities(
            Principal principal, List<CapabilityManifest> candidates);

    /**
     * 执行授权：判断是否允许调用指定能力。
     */
    boolean authorizeExecution(
            Principal principal, String capabilityId, String version);

    /**
     * 管理操作授权：判断是否允许执行管理操作（导入/发布/审批）。
     */
    boolean authorizeAdmin(Principal principal, AdminAction action);
}

/**
 * 请求上下文：抽象 HTTP 请求中的认证相关信息。
 * 使 Domain 层不依赖 Servlet API。
 */
public record RequestContext(
    Map<String, String> headers,
    Map<String, String> cookies,
    Map<String, String> queryParams,
    String remoteAddr
) {}
```

### 3.3 参考实现：Sa-Token

| 维度 | 说明 |
|------|------|
| 选型理由 | 轻量（核心 jar < 500KB）、无框架绑定、支持多种 Token 风格、国内社区活跃 |
| 引入方式 | `sa-token-core`（不引入 `sa-token-spring-boot3-starter`，避免自动配置侵入） |
| 集成模式 | 在 `gateway-adapter-auth-satoken` 模块中实现 `AuthenticationPort` |
| Token 模式 | Bearer Token（JWT 风格），支持从 Header/Cookie/QueryParam 多源读取 |
| 会话存储 | 单机模式内存 → 生产模式 Redis（复用组件二的 Redis 基础设施） |

**模块结构：**
```
gateway-adapter-auth-satoken/
├── pom.xml                    (依赖 sa-token-core + gateway-domain)
└── src/main/java/
    └── com/ai/gateway/adapter/auth/satoken/
        ├── SaTokenAuthenticationAdapter.java   (implements AuthenticationPort)
        ├── SaTokenAuthorizationAdapter.java    (implements AuthorizationPort)
        └── SaTokenConfig.java                  (Sa-Token 参数配置)
```

**可替换性保证：**
- 接入方只需实现 `AuthenticationPort` + `AuthorizationPort` 两个接口
- 通过 Spring `@ConditionalOnProperty` 或 `@Profile` 切换实现
- 示例：`gateway.auth.provider=sa-token | oauth2 | cas | custom`

### 3.4 备选方案对比

| 方案 | 优势 | 劣势 | 适用场景 |
|------|------|------|---------|
| **Sa-Token** ✅ | 轻量、灵活、学习成本低 | 国际知名度低 | 国内团队、快速集成 |
| Spring Security OAuth2 | 生态完善、标准化 | 重量级、配置复杂 | 已有 Spring Security 体系 |
| CAS Client | 企业 SSO 标准 | 部署依赖 CAS Server | 已有 CAS 基础设施 |
| 自研 JWT 验签 | 完全可控 | 需自行处理刷新/吊销 | 极简场景 |

### 3.5 决策

> **采用 Sa-Token 作为参考实现**，接口设计保持框架无关。  
> 接入方可通过实现 Port 接口 + 条件装配替换为任意认证方案。

---

## 4. 组件二：分布式快照缓存（Redis）

### 4.1 需求背景

当前 `InMemoryCatalogManager` 将快照存储在 JVM 内存中：
- 单实例可用，多实例部署时各节点快照不一致
- 重启后需从 PostgreSQL 全量加载（冷启动慢）
- 发布后需逐实例通知刷新（当前 SnapshotNotifier 为 Stub）

### 4.2 技术方案

```
┌─────────────┐     publish      ┌──────────────┐
│  Admin API  │ ───────────────→ │  PostgreSQL  │  (持久化，Source of Truth)
└─────────────┘                  └──────┬───────┘
                                        │ saveSnapshot()
                                        ▼
                                 ┌──────────────┐
                                 │    Redis     │  (分布式缓存 + Pub/Sub 通知)
                                 │  - 快照数据   │
                                 │  - 发布通知   │
                                 └──────┬───────┘
                                        │ Pub/Sub: "snapshot:published"
                    ┌───────────────────┼───────────────────┐
                    ▼                   ▼                   ▼
             ┌────────────┐     ┌────────────┐     ┌────────────┐
             │ Instance-1 │     │ Instance-2 │     │ Instance-N │
             │ Local Cache│     │ Local Cache│     │ Local Cache│
             └────────────┘     └────────────┘     └────────────┘
```

### 4.3 技术选型

| 维度 | 选型 | 理由 |
|------|------|------|
| 客户端 | **Redisson**（`redisson-spring-boot-starter`） | 功能丰富（分布式锁、Pub/Sub、本地缓存）、开箱即用 |
| 引入方式 | `redisson-spring-boot-starter` | 自动配置 RedissonClient，兼容 Spring Data Redis API |
| 数据结构 | String（JSON 序列化快照） + Pub/Sub Channel | 简单可靠 |
| 缓存策略 | Write-Through（发布时写 Redis）+ 本地 L1（Caffeine 30s TTL） | 两级缓存降低 Redis 压力 |
| 一致性 | 最终一致（Pub/Sub 通知 + 版本号校验） | 快照发布为低频操作，秒级延迟可接受 |

### 4.4 实现要点

**SnapshotNotifier 真实实现：**
```java
// gateway-adapter-redis 模块
public class RedisSnapshotNotifier implements SnapshotNotifier {
    private final RedissonClient redissonClient;
    private static final String CHANNEL = "gateway:snapshot:published";

    @Override
    public void notifySnapshotPublished(long snapshotVersion) {
        RTopic topic = redissonClient.getTopic(CHANNEL);
        topic.publish(String.valueOf(snapshotVersion));
    }
}
```

**CatalogPort Redis 装饰器：**
```java
public class RedisCatalogPortDecorator implements CatalogPort {
    // 读：Redis → miss → PostgreSQL → 回填 Redis
    // 写：PostgreSQL → Redis（Write-Through）
}
```

**本地 L1 缓存（InMemoryCatalogManager 保留）：**
- 收到 Pub/Sub 通知 → 从 Redis 加载新快照 → 原子替换本地引用
- 启动时：Redis → miss → PostgreSQL → 回填

### 4.5 Redis Key 设计

```
gateway:snapshot:{environment}:latest    → JSON(CatalogSnapshot)
gateway:snapshot:{environment}:version   → long (当前版本号)
gateway:channel:snapshot-published       → Pub/Sub channel
```

### 4.6 决策

> **采用 Redis（Redisson）作为分布式快照缓存 + 发布通知通道。**  
> 保留 InMemoryCatalogManager 作为 L1 本地缓存，Redis 作为 L2 + 通知总线。

---

## 5. 组件三：流量控制（Sentinel）

### 5.1 需求背景

网关作为所有 AI 能力调用的统一入口，需要：
- **QPS 限流**：防止突发流量击穿后端 Provider
- **并发控制**：限制同时进行的 Dubbo 调用数（保护 Provider 线程池）
- **熔断降级**：Provider 异常率超阈值时快速失败
- **热点参数限流**：按 capabilityId 粒度独立限流

### 5.2 技术选型

| 维度 | 选型 | 理由 |
|------|------|------|
| 组件 | **Sentinel Core**（`sentinel-core`） | 仅引入核心 jar，不引入 Dashboard/Transport |
| 版本 | 1.8.8+ | 支持 JDK 17、Jakarta EE |
| 集成方式 | 编程式 API（`SphU.entry()`） | 不依赖 Spring Cloud Alibaba 全家桶 |
| 规则存储 | 初期硬编码 → 后续 Nacos DataSource | 渐进式 |
| 监控 | Sentinel 日志 → 后续接入 Dashboard | 最小化初始复杂度 |

### 5.3 集成模式

```java
// gateway-adapter-sentinel 模块
public class SentinelRateLimiterAdapter implements RateLimiterPort {

    @Override
    public boolean tryAcquire(String capabilityId, int permits) {
        Entry entry = null;
        try {
            entry = SphU.entry("gateway:capability:" + capabilityId,
                    EntryType.IN, permits);
            return true;
        } catch (BlockException e) {
            return false;  // 被限流
        } finally {
            if (entry != null) entry.exit();
        }
    }

    @Override
    public void recordSuccess(String capabilityId, long durationMs) {
        // Sentinel 自动统计（通过 entry 的 RT 记录）
    }

    @Override
    public void recordFailure(String capabilityId, Throwable error) {
        Trace.trace(error);  // 记录异常用于熔断判断
    }
}
```

### 5.4 限流规则设计

| 资源名 | 限流维度 | 阈值（初始） | 策略 |
|--------|---------|-------------|------|
| `gateway:capability:{id}` | 单能力 QPS | 100/s | 快速失败 |
| `gateway:capability:{id}` | 单能力并发 | 50 | 排队等待（500ms） |
| `gateway:global` | 全局 QPS | 2000/s | 快速失败 |
| `gateway:llm:routing` | LLM 调用 QPS | 20/s | 排队等待（2s） |

### 5.5 熔断规则

| 资源名 | 熔断策略 | 阈值 | 恢复时间 |
|--------|---------|------|---------|
| `gateway:capability:{id}` | 异常比例 | >50%（10s 窗口） | 30s 半开 |
| `gateway:capability:{id}` | 慢调用比例 | >80% RT>3s | 60s 半开 |

### 5.6 决策

> **采用 Sentinel Core（编程式 API）作为流量控制引擎。**  
> 通过 `RateLimiterPort` 接口隔离，后续可替换为 Resilience4j 或自研方案。

---

## 6. 组件四：分布式通知与事件（补充）

### 6.1 需求

除快照发布通知外，生产环境还需要：
- 能力状态变更通知（导入/审批/下线）
- 审计事件异步投递
- 多实例间配置同步

### 6.2 选型

| 场景 | 方案 | 理由 |
|------|------|------|
| 快照通知（低延迟） | **Redis Pub/Sub** | 复用组件二基础设施，秒级送达 |
| 审计事件（可靠投递） | **PostgreSQL Outbox** → 定时轮询 | 已有 OutboxRelay 实现，无需额外 MQ |
| 未来高吞吐事件 | 预留 Pulsar/Kafka 接口 | 当前 OutboxPort 已抽象 |

### 6.3 决策

> 快照通知复用 Redis Pub/Sub；审计事件保持 Outbox 模式；不引入额外 MQ。

---

## 7. 组件五：分布式锁（补充）

### 7.1 需求

- 快照发布原子性（防止并发发布产生版本冲突）
- Outbox 轮询防重（多实例不重复消费）
- 管理操作互斥（同一能力不能并发审批）

### 7.2 选型

| 方案 | 优势 | 劣势 |
|------|------|------|
| **Redisson RLock** ✅ | 可重入、自动续期（Watchdog）、红锁支持、复用 Redisson 客户端 | 相比原生 SETNX 稍重 |
| Redis SETNX + Lua | 轻量 | 需自行处理续期、不可重入 |
| 数据库行锁 | 无额外依赖 | 性能差、不适合高频 |
| ZooKeeper | 强一致 | 运维成本高 |

### 7.3 决策

> **采用 Redisson 内置 RLock 实现分布式锁。**  
> 封装为 `DistributedLockPort` 接口，由 Redisson 自动处理续期和可重入。

---

## 8. 组件六：LLM 供应商抽象（补充）

### 8.1 需求

当前仅支持 DeepSeek，生产需要：
- 多供应商故障切换（DeepSeek → OpenAI → 文心）
- 按能力复杂度路由到不同模型
- Token 用量统计与成本控制

### 8.2 现有基础

`LlmRouterPort` 接口已定义，`HttpLlmRouterAdapter` 为当前实现。

### 8.3 扩展方案

```
gateway-adapter-llm-http/          (当前：DeepSeek 直连)
gateway-adapter-llm-multi/         (新增：多供应商路由)
├── LlmProviderChain.java          (责任链：primary → fallback)
├── provider/
│   ├── DeepSeekProvider.java
│   ├── OpenAiProvider.java
│   └── WenxinProvider.java
└── LlmCostTracker.java            (Token 计量)
```

### 8.4 决策

> 当前阶段保持 DeepSeek 单供应商，接口预留多供应商扩展点。  
> 后续按需增加 Provider 实现，通过配置切换优先级。

---

## 9. 依赖版本规划

| 组件 | GroupId | ArtifactId | 版本 | 引入模块 |
|------|---------|-----------|------|--------|
| Sa-Token Core | cn.dev33 | sa-token-core | 1.39.0 | gateway-adapter-auth-satoken |
| Sa-Token JWT | cn.dev33 | sa-token-jwt | 1.39.0 | gateway-adapter-auth-satoken |
| Sa-Token Redis | cn.dev33 | sa-token-redisson-jackson | 1.39.0 | gateway-adapter-auth-satoken |
| Redisson | org.redisson | redisson | 3.40.2 | gateway-adapter-redis |
| Sentinel | com.alibaba.csp | sentinel-core | 1.8.8 | gateway-bootstrap |
| Caffeine | com.github.ben-manes.caffeine | caffeine | 3.1.8 | gateway-application（L1 缓存） |

---

## 10. 新增模块规划

```
ai-capability-gateway/
├── gateway-domain/                   (Port 接口定义)
├── gateway-application/              (用例编排)
├── gateway-adapter-web/              (REST 控制器)
├── gateway-adapter-postgresql/       (持久化)
├── gateway-adapter-redis/            ← 新增：Redisson 基础设施（缓存/通知/分布式锁）
├── gateway-adapter-auth-satoken/     ← 新增：Sa-Token 认证授权适配器
├── gateway-adapter-dubbo/            (已有：Dubbo 泛化调用)
├── gateway-adapter-llm-http/         (已有：LLM 调用)
├── gateway-bootstrap/                (启动装配 + Sentinel 限流实现)
├── gateway-test-provider/            (已有：E2E 测试 Provider)
└── ...
```

### 模块拆分决策

| 组件 | 是否单独模块 | 理由 |
|------|:---:|------|
| Redis（Redisson） | ✅ 是 | 共享基础设施，实现 3+ 个 Port（SnapshotNotifier、CatalogPort 装饰器、DistributedLockPort），且 auth 模块依赖其 RedissonClient Bean |
| Sentinel | ❌ 否，合入 bootstrap | 仅实现 1 个 Port（RateLimiterPort），代码量极小（~100 行），属于横切关注点，在启动层装配更自然 |
| Sa-Token | ✅ 是 | 独立的认证授权实现，依赖 sa-token-core + sa-token-jwt + sa-token-dao-redisson，职责边界清晰 |

**Sentinel 代码放置位置：**
```
gateway-bootstrap/src/main/java/com/ai/gateway/bootstrap/ratelimit/
├── SentinelRateLimiterAdapter.java    (implements RateLimiterPort)
└── SentinelRuleInitializer.java       (@PostConstruct 加载硬编码规则)
```

---

## 11. 集成优先级与里程碑

| 阶段 | 组件 | 交付物 | 预估工期 | 实现状态 |
|------|------|--------|---------|---------|
| **M1** | 权限验证（Sa-Token） | 可插拔 Auth 接口 + Sa-Token 参考实现 | 3 天 | ✅ 已实现（gateway-adapter-auth-satoken） |
| **M2** | Redis 快照缓存 | 多实例快照一致性 + Pub/Sub 热加载 | 2 天 | ✅ 已实现（gateway-adapter-redis） |
| **M3** | Sentinel 限流 | 能力级 QPS/并发/熔断 | 2 天 | ✅ 已实现（合入 gateway-bootstrap） |
| **M4** | 分布式锁 | 发布原子性 + Outbox 防重 | 1 天 | ✅ 已实现（DistributedLockPort + Redisson） |
| **M5** | 集成测试 | E2E 自动化（含多实例场景） | 2 天 | 🟡 测试已就绪（Testcontainers，执行需 Docker/CI） |

> **实现说明（2026-08-01）**：
> - 三个可插拔开关：`gateway.auth.provider`（stub|sa-token）、`gateway.cache.provider`（stub|redis）、`gateway.ratelimit.provider`（stub|sentinel），默认均为 stub，不影响存量行为。
> - §9 表中 Sa-Token Redis 工件名修正为 `sa-token-redisson-jackson`（`sa-token-dao-redisson` 在 1.39.0 不存在，系早期命名）。
> - M1 认证主路径采用无状态 JWT 验签（符合决策 #2）；Sa-Token 会话经 `SaTokenRedisDaoConfiguration` 持久化到共享 Redis（符合决策 #1），在 sa-token + redis 同时启用时生效。
> - Redisson 实际引入 `redisson`（编程式 `Config` + 手动 `RedissonClient` Bean）而非 `redisson-spring-boot-starter`，以遵循“最小侵入、避免自动配置侵入”原则；Redis 为唯一新增中间件（约束 #5）。

---

## 12. 已确认决策

> 以下决策已于 2026-07-28 评审确认。

| # | 决策项 | 结论 | 备注 |
|---|--------|------|------|
| 1 | Sa-Token 会话存储 | **Redis** | 复用组件二 Redis 基础设施，Sa-Token 集成 `sa-token-dao-redisson` |
| 2 | 认证 Token 格式 | **JWT** | 无状态、可离线验签、支持跨服务透传 |
| 3 | Sentinel 规则管理 | **硬编码** | 初期以代码方式定义规则，后续按需接入 Nacos DataSource |
| 4 | API Key 认证 | **不需要** | 统一使用 JWT Token 认证，不额外引入 API Key 机制 |
| 5 | 授权粒度 | **能力级（capabilityId）** | 初期按 capabilityId 维度控制访问权限，满足当前需求 |

### 确认后的技术要点补充

**Sa-Token + Redis（Redisson）+ JWT 集成方案：**
- 引入 `sa-token-core` + `sa-token-dao-redisson`（会话持久化到 Redis，复用 Redisson 客户端）
- Token 风格配置为 `jwt`（Sa-Token 内置 JWT 扩展：`sa-token-jwt`）
- Redis Key 前缀：`gateway:auth:session:{token}`
- Token 有效期：Access Token 2h，Refresh Token 7d（可配置）

**Sentinel 硬编码规则示例：**
```java
// 在 gateway-adapter-sentinel 模块的 @PostConstruct 中初始化
FlowRule rule = new FlowRule("gateway:capability:" + capabilityId)
    .setCount(100)           // QPS 阈值
    .setGrade(RuleConstant.FLOW_GRADE_QPS)
    .setStrategy(RuleConstant.STRATEGY_DIRECT);
FlowRuleManager.loadRules(List.of(rule));
```

**授权模型（能力级）：**
```java
// AuthorizationPort 实现中的核心判断逻辑
boolean authorized = principal.roles().stream()
    .anyMatch(role -> capabilityAcl.contains(capabilityId, role));
// capabilityAcl: 能力-角色映射表，存储在 PostgreSQL，管理 API 维护
```

---

## 13. 架构约束

1. **Domain 层零依赖**：所有新增组件的接口定义在 `gateway-domain`，实现放在独立 adapter 模块
2. **条件装配**：通过 `@ConditionalOnProperty(prefix="gateway.xxx")` 控制 adapter 激活
3. **无 Sentinel Dashboard 依赖**：仅使用 sentinel-core 编程式 API
4. **无 Spring Cloud 依赖**：不引入 spring-cloud-alibaba 全家桶
5. **Redis（Redisson）为唯一新增中间件**：认证会话、快照缓存、Pub/Sub、分布式锁复用同一 Redis 实例
