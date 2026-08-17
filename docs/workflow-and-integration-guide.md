# AI 能力网关 — 工作流程、原理与接入指南

## 目录

1. [系统概述](#1-系统概述)
2. [核心工作原理](#2-核心工作原理)
3. [控制面工作流程](#3-控制面工作流程)
4. [运行面工作流程](#4-运行面工作流程)
5. [确定性执行链路](#5-确定性执行链路)
6. [写操作二阶段协议](#6-写操作二阶段协议)
7. [安全模型](#7-安全模型)
8. [接入指南](#8-接入指南)
9. [运维与可观测性](#9-运维与可观测性)
10. [常见问题](#10-常见问题)

---

## 1. 系统概述

### 1.1 定位

AI 能力网关是一个**独立部署的中间层**，将经过治理的微服务 API 转换为可被自然语言发现和调用的"能力"。它不是通用 Agent，不是工作流引擎，也不允许模型自由组合多步调用。

### 1.2 核心闭环

```
微服务 API ──→ Capability Manifest ──→ 校验/确认/发布 ──→ 不可变快照
                                                              │
用户自然语言 ──→ 认证 ──→ 候选检索 ──→ LLM选择 ──→ 确定性执行 ──→ 结果
```

### 1.3 设计原则

| 原则 | 含义 |
|------|------|
| 模型不是信任边界 | LLM 输出始终是不可信输入，所有安全决策由确定性代码完成 |
| 先授权，后暴露 | 未授权能力的名称和存在性不暴露给模型或用户 |
| 控制面与运行面分离 | 控制面管理生命周期；运行面只读不可变快照 |
| 最小能力面 | 一个 Capability 只表达一个清晰的业务动作 |
| 原生 API 是事实源 | Manifest 是原生契约的"可执行投影"，不取代原生 API |

### 1.4 总体架构

```
                         ┌──────────────────────┐
Manifest / OpenAPI /     │      控制面 API      │
Proto / API JAR(离线) ──>│ 导入-校验-测试-确认  │
                         │ 发布-停用-回滚       │
                         └──────────┬───────────┘
                                    │ immutable snapshot
                                    v
┌────────┐  JWT/OIDC  ┌──────────────────────────────────────┐
│ Client │───────────>│              运行面                  │
└────────┘            │ 认证 -> 授权过滤 -> BM25 候选检索   │
                      │ -> LLM 选择/提参 -> 确定性校验       │
                      │ -> 参数绑定 -> 协议适配 -> 结果治理  │
                      └──────┬───────────────┬───────────────┘
                             │               │
                             v               v
                      ┌────────────┐   ┌──────────────┐
                      │ LLM 服务   │   │ 协议 Provider│
                      └────────────┘   └──────────────┘
```

---

## 2. 核心工作原理

### 2.1 LLM 的角色边界

LLM 在本网关中的职责被**严格压缩**为：

1. 在网关给出的 Top-K 候选集合中**选择一个能力**
2. 为该能力的公开 Input Schema **提取业务参数**

LLM **不知道**也**不能控制**：
- 网关有多少能力（只看到临时候选子集）
- 能力的真实 ID（只看到短别名 `cap_<hash>`）
- 协议配置（接口名、地址、序列化方式）
- 租户/身份信息（orgId 由系统注入）
- 授权策略（由确定性代码执行）

### 2.2 候选检索机制

```
全量已发布目录（内存 BM25 索引）
  → 授权过滤：去掉当前 Principal 无权的能力
  → BM25 召回 Top-K：用用户自然语言文本做词法检索
  → 短别名包装：每个候选生成 cap_<hash> 别名
  → 发送给 LLM：只附带公开说明和 inputSchema
```

BM25 索引内容：
- displayName（能力显示名）
- 业务动作描述
- 正例、反例和同义词
- 领域标签
- 公开字段名及业务描述

### 2.3 短别名机制

每次请求为候选能力生成短别名：

```
cap_<base32(sha256(snapshotVersion + capabilityId + version))[0:16]>
```

请求内维护映射：`alias → capabilityId + version + manifestDigest`

模型只接收别名，不接收真实 capabilityId，防止模型记忆或猜测能力标识。

### 2.4 不可变快照

- 发布操作生成新的单调递增 `snapshot_version`
- 正在执行的请求固定使用开始时的快照版本
- 快照内容不可修改，只能生成新版本
- 各实例通过 AtomicReference 原子切换内存引用

---

## 3. 控制面工作流程

### 3.1 能力生命周期

```
DRAFT → VALIDATED → APPROVED → PUBLISHED → SUSPENDED → RETIRED
              │                            │
              └────→ REJECTED              └────→ VALIDATED
```

| 状态 | 含义 | 允许操作 |
|------|------|---------|
| DRAFT | 已导入，允许编辑 | 编辑、提交校验 |
| VALIDATED | 通过 10 步校验 | 确认、驳回 |
| APPROVED | 确认通过 | 发布 |
| PUBLISHED | 进入活动快照 | 停用、新版本 |
| SUSPENDED | 紧急停用 | 恢复（需重新校验） |
| RETIRED | 永久退出 | 无 |
| REJECTED | 校验/确认拒绝，当前版本终止 | 导入新的 id+version |

### 3.2 导入与校验（10 步流水线）

```
Step 1:  文件大小、格式和 Manifest JSON Schema 校验
Step 2:  ID、版本、Owner、描述和示例完整性校验
Step 3:  输入/输出 JSON Schema 安全校验
Step 4:  参数位置、类型、来源、converter 白名单校验
Step 5:  projection 目标唯一性、脱敏路径存在性校验
Step 6:  权限、风险、超时、重试和容量限制校验
Step 7:  协议地址引用、接口和类型白名单校验
Step 8:  测试环境连通性和兼容性测试
Step 9:  与活动版本的兼容性分析
Step 10: 生成内容 SHA-256 摘要和校验报告
```

**关键约束**：校验不得修改原始内容。自动修复只能生成新草案供 Owner 确认。

### 3.3 确认流程

1. 通过 10 步校验后，系统生成确认摘要
2. 提交者审阅摘要，一键确认或驳回
3. 确认后状态从 VALIDATED → APPROVED
4. 确认记录绑定 Manifest 摘要、确认人、时间

### 3.4 发布流程

发布在**单个数据库事务**中完成：

1. 校验目标版本仍为 APPROVED
2. 生成新的单调递增 snapshot_version
3. 固化该环境所有活动能力及策略引用
4. 将新快照标记为当前版本
5. 写入发布审计和通知事件

各实例收到通知后：加载快照 → 构建检索索引 → 校验摘要 → 原子替换内存引用。

### 3.5 回滚与紧急停用

- **回滚**：将历史快照内容复制为新版本，不修改历史记录
- **紧急停用**：生成新快照排除该能力，通过高优先级通知传播
- 运行面在调用 Provider 前查询本地停用表

---

## 4. 运行面工作流程

### 4.1 自然语言路由流水线（11 步）

```
Step 1:  认证并构造 Principal
Step 2:  固定目录快照（请求内不变）
Step 3:  权限和环境预过滤
Step 4:  文本规范化（去噪、分词准备）
Step 5:  BM25 Top-K 候选检索
Step 6:  阈值/歧义初筛
Step 7:  构造请求内短别名候选
Step 8:  LLM 选择能力并提取 MODEL 参数
Step 9:  选择合法性与 Schema 校验
Step 10: 执行前再次授权
Step 11: 调用 Provider 或发起澄清
```

### 4.2 详细流程说明

#### Step 1: 认证

```
Client → Authorization: Bearer <token>
       → AuthenticationPort.authenticate(token)
       → Principal { subject, orgId, roles, permissions, authTime }
```

认证失败 → 立即返回 `AUTHENTICATION_FAILED`，不暴露任何能力信息。

#### Step 2: 固定快照

```
CatalogPort.loadCurrentSnapshot(environment)
→ CatalogSnapshot { snapshotVersion, capabilities[] }
```

请求内所有后续步骤使用同一快照版本，不受并发发布影响。

#### Step 3: 授权过滤

按 Principal 过滤能力可见性。首期简化为：已认证即可见全部只读能力。

#### Step 4-5: 文本规范化 + BM25 检索

```
用户文本 → TextNormalizer.normalize()
         → CandidateRetriever.retrieve(normalizedText, visibleCapabilities, topK)
         → List<ScoredCapability>（按 BM25 分数排序）
```

#### Step 6: 阈值初筛

- 检索分数低于阈值 → NO_MATCH
- Top-1 与 Top-2 分差过小 → 可能触发澄清
- 候选数为 0 → NO_MATCH

#### Step 7: 短别名构造

```
AliasGenerator.generateAliases(snapshotVersion, candidates)
→ Map<String, CapabilityManifest>  // alias → manifest
```

#### Step 8: LLM 路由

```
LlmRouterPort.route(candidates, userText, locale)
→ ModelDecision { decision: SELECT|CLARIFY|NO_MATCH, alias, arguments }
```

LLM 返回三种决策之一：
- `SELECT`：选中一个别名 + 提取的参数
- `CLARIFY`：需要用户补充信息
- `NO_MATCH`：候选中无匹配

#### Step 9: 确定性校验

- 验证 alias 属于本次候选集
- 验证参数满足 Input Schema
- 验证无保留字段注入（class, @type）

#### Step 10: 执行前再次授权

```
AuthorizationPort.authorizeExecution(principal, capabilityId, version)
```

防止检索与执行之间发生策略变化。

#### Step 11: 执行或澄清

- READ_ONLY → 立即调用 DeterministicExecutionUseCase
- WRITE_LOW/WRITE_HIGH → 返回执行计划，进入二阶段协议
- CLARIFY → 创建澄清会话，返回 interactionId

### 4.3 澄清会话

```
用户: "帮我查询一下订单"
网关: CLARIFICATION_REQUIRED
      interactionId: "abc-123"
      question: "请提供您要查询的订单号"

用户: "SO202607210001"（继续澄清）
网关: COMPLETED + 订单数据
```

**约束**：
- 会话有短期过期时间（默认 5 分钟）
- 后续回答只能补充缺失信息
- 意图跳出（用户改变话题）→ 失效当前会话，重新走完整路由
- Principal 改变/能力停用/策略变化 → 强制重新开始

---

## 5. 确定性执行链路

### 5.1 参数绑定（8 步）

```
Step 1: 解析模型输出 JSON（拒绝重复键和非有限数）
Step 2: 验证 Input Schema
Step 3: 执行格式/长度/枚举/业务预约束
Step 4: 解析非模型字段（PRINCIPAL / CONSTANT / SYSTEM）
Step 5: 按静态映射构造协议参数（位置对齐）
Step 6: 执行类型和大小校验
Step 7: 授权（由调用方完成）
Step 8: 协议调用（由调用方完成）
```

### 5.2 参数来源

| 来源 | 含义 | 示例 |
|------|------|------|
| MODEL | LLM 从用户文本提取 | orderNo, sortField |
| PRINCIPAL | 从认证身份注入 | orgId, userId |
| CONSTANT | Manifest 中固定值 | apiVersion |
| SYSTEM | 运行时系统值 | traceId, timestamp |

### 5.3 协议调用

首期使用 Dubbo GenericService 泛化调用：

```
InvocationAdapter.invoke(InvocationRequest)
→ GenericService.$invoke(method, parameterTypes, args)
→ InvocationResult { protocolStatus, data, errorCode }
```

**关键约束**：
- 不加载业务 API JAR
- 接口名/方法名/参数类型只是字符串元数据
- 序列化白名单：仅 hessian2、fastjson2

### 5.4 结果治理（8 步）

```
Step 1: 转换为中立 JSON 树（剥除 class 等协议元数据键）
Step 2: 检查响应大小、深度、集合长度
Step 3: 按 Envelope 规则判定业务成功并提取数据
Step 4: 按 projection 白名单映射公开字段
Step 5: 执行字段脱敏（PARTIAL_MASK / FULL_MASK / HASH）
Step 6: 校验公开输出 Schema
Step 7: 生成结构化结果
Step 8: 可选自然语言摘要
```

---

## 6. 写操作二阶段协议

### 6.1 状态机

```
PREPARED → CONFIRMED → SUCCEEDED
    │           │
    │           └──→ FAILED
    │           └──→ UNKNOWN
    └──→ EXPIRED
    └──→ CANCELLED
```

### 6.2 流程

```
Phase 1: Prepare
  用户: "取消订单 SO202607210001"
  网关: 参数绑定 → 授权 → 持久化操作记录 → 返回 confirmationToken
  
Phase 2: Confirm
  用户: 确认执行（携带 confirmationToken）
  网关: CAS 原子认领 → 调用 Provider → 记录终态
  
Phase 3: Status（可选）
  用户: 查询操作状态
  网关: 返回 SUCCEEDED / FAILED / UNKNOWN
```

### 6.3 安全保障

- 幂等键防重复提交
- CAS 防并发确认
- confirmationToken 有过期时间
- Confirm 时重新检查认证新鲜度
- 能力已 SUSPENDED/RETIRED → 拒绝执行

---

## 7. 安全模型

### 7.1 信任边界

```
┌─────────────────────────────────────────────────────┐
│ 不可信区域（LLM 输出、用户输入）                      │
│  - 能力选择（alias）                                 │
│  - 业务参数（MODEL 来源）                            │
└──────────────────────┬──────────────────────────────┘
                       │ 确定性校验
                       v
┌─────────────────────────────────────────────────────┐
│ 可信区域（网关确定性代码）                            │
│  - 认证/授权决策                                     │
│  - 协议配置（接口名、地址、序列化）                   │
│  - 租户注入（orgId）                                 │
│  - 超时/重试/限流策略                                │
│  - 快照版本和候选集                                  │
└─────────────────────────────────────────────────────┘
```

### 7.2 模型隔离

LLM 不接收也不得输出：
- 协议 Binding（接口名、方法名、地址）
- 服务注册中心信息
- 租户/用户身份
- 序列化方式
- 超时和重试配置
- 授权策略

### 7.3 审计

所有请求记录三个检查点：
1. **REQUEST_ACCEPTED**：认证通过后立即记录
2. **STARTED**：Provider 调用前记录
3. **TERMINAL**：最终状态（SUCCEEDED / FAILED / 错误码）

审计使用 Micro-batching 分组提交（ArrayBlockingQueue + 专职线程），Fail Closed 语义。

---

## 8. 接入指南

### 8.1 接入前提

| 条件 | 说明 |
|------|------|
| Provider 已注册 | Dubbo 服务已在 Nacos 注册中心注册 |
| 接口稳定 | 方法签名、参数类型不会频繁变更 |
| 统一响应 | Provider 使用可预测的响应包装结构 |
| 序列化兼容 | 使用 hessian2 或 fastjson2 |
| 身份认证 | 调用方能获取有效的 JWT/SSO Token |

### 8.2 接入步骤

#### 第一步：编写 Capability Manifest

```yaml
apiVersion: gateway.ai/v1
kind: Capability

metadata:
  id: order.detail.query          # 领域.资源.动作
  version: 1.0.0                  # 语义化版本
  owner:
    team: order-platform
    contact: order-platform@example.com
  tags: [order, query, read-only]

spec:
  displayName: 查询订单详情
  description: 根据订单号查询当前组织下的单个订单详情。
  examples:
    positive:
      - 查询订单 SO202607210001
      - 看一下 SO202607210001 的当前状态
      - 帮我找订单号为 SO202607210001 的订单
    negative:
      - 查询今天创建的全部订单
      - 取消订单 SO202607210001
    synonyms: [订单详情, 订单状态, 订单信息]

  risk: READ_ONLY

  inputSchema:
    type: object
    additionalProperties: false    # 必须为 false
    properties:
      request:
        type: object
        additionalProperties: false
        properties:
          orderNo:
            type: string
            pattern: "^SO[0-9]{12}$"
            description: 业务订单号
        required: [orderNo]
    required: [request]

  invocation:
    protocol: DUBBO
    registryRef: nacos-main
    interfaceName: com.example.order.api.OrderQueryApi
    method: query
    parameterTypes:
      - java.lang.Long
      - com.example.order.api.OrderQueryRequest
    serialization: fastjson2
    arguments:
      - position: 0
        name: orgId
        protocolType: java.lang.Long
        source: PRINCIPAL          # 从认证身份注入
        sourcePath: /orgId
      - position: 1
        name: request
        protocolType: com.example.order.api.OrderQueryRequest
        source: MODEL              # 由 LLM 从用户文本提取
        sourcePath: /request

  output:
    mode: ENVELOPE
    envelope:
      codePath: /code
      successValues: ["200"]
      dataPath: /value
      messagePath: /message
    projection:
      - from: /orderNo
        to: /orderNo
      - from: /status
        to: /status
      - from: /amount
        to: /amount
    publicSchema:
      type: object
      additionalProperties: false
      properties:
        orderNo: { type: string }
        status: { type: string }
        amount: { type: number }
      required: [orderNo, status]
    redactions:
      - path: /customerName
        method: PARTIAL_MASK
    maxBytes: 262144

  resilience:
    timeoutMs: 1500
    retries: 1
    maxConcurrent: 50
```

#### 第二步：本地校验

```bash
# 使用 CLI 工具校验 Manifest
java -jar gateway-manifest-cli.jar validate \
  --manifest order-detail-query.yaml \
  --schema gateway-contract-schema/src/main/resources/schema/capability-manifest.schema.json
```

#### 第三步：导入网关

```bash
curl -X POST http://localhost:8080/admin/v1/manifests:import \
  -H "Authorization: Bearer <admin-token>" \
  -H "Content-Type: application/json" \
  --data-binary @order-detail-query.json
```

#### 第四步：确认并发布

```bash
# 确认
curl -X POST http://localhost:8080/admin/v1/capabilities/order.detail.query/versions/1.0.0:approve \
  -H "Authorization: Bearer <admin-token>" \
  -H "Content-Type: application/json" \
  -d '{"approver":"admin"}'

# 发布到 production
curl -X POST http://localhost:8080/admin/v1/releases:publish \
  -H "Authorization: Bearer <admin-token>" \
  -H "Content-Type: application/json" \
  -d '{"environment": "production"}'
```

#### 第五步：验证调用

```bash
curl -X POST http://localhost:8080/api/v1/natural-language/queries \
  -H "Authorization: Bearer <user-token>" \
  -H "Content-Type: application/json" \
  -d '{"requestId": "test-001", "text": "查询订单 SO202607210001", "locale": "zh-CN"}'
```

### 8.3 Manifest 编写要点

| 要点 | 说明 |
|------|------|
| `additionalProperties: false` | inputSchema 和 publicSchema 必须声明，防止模型注入额外字段 |
| 参数来源分离 | MODEL 字段由 LLM 提取；PRINCIPAL/SYSTEM 由系统注入，模型不可见 |
| 语义描述充分 | 至少 3 个正例、2 个反例、同义词列表 |
| Envelope 匹配实际 | successValues 必须与 Provider 实际返回一致（字符串 "200" ≠ 数字 200） |
| projection 白名单 | 未映射字段不会离开网关 |
| 序列化白名单 | 仅 hessian2 或 fastjson2 |
| 保留字段拒绝 | 不得出现 class、@type、@class 等字段 |

### 8.4 API 参考

#### 运行面 API

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/natural-language/queries` | 自然语言查询 |
| POST | `/api/v1/natural-language/interactions/{id}/messages` | 继续澄清 |
| POST | `/api/v1/natural-language/actions:prepare` | 写操作 Prepare |
| POST | `/api/v1/operations/{id}:confirm` | 写操作 Confirm |
| GET | `/api/v1/operations/{id}` | 查询操作状态 |

#### 控制面 API

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/admin/v1/manifests:import` | 导入 JSON Manifest |
| POST | `/admin/v1/capabilities/{id}/versions/{version}:approve` | 确认 |
| POST | `/admin/v1/releases:publish` | 发布快照 |
| POST | `/admin/v1/capabilities/{id}:suspend` | 紧急停用 |
| POST | `/admin/v1/releases:rollback` | 回滚 |

#### 健康检查

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/actuator/health` | 就绪探针 |
| GET | `/actuator/prometheus` | Prometheus 指标 |

---

## 9. 运维与可观测性

### 9.1 部署架构

- 无状态多实例部署
- 每个实例内存持有不可变快照 + BM25 索引
- 快照通过 PostgreSQL LISTEN/NOTIFY 或轮询同步
- 加载失败的实例保持旧快照，超过最大滞后时间退出就绪

### 9.2 关键指标

| 指标 | 说明 |
|------|------|
| `gateway.nl.routing.duration` | 路由流水线耗时 |
| `gateway.llm.call.duration` | LLM 调用耗时 |
| `gateway.provider.call.duration` | Provider 调用耗时 |
| `gateway.audit.queue.depth` | 审计队列深度 |
| `gateway.snapshot.lag` | 快照同步滞后 |
| `gateway.clarification.active` | 活跃澄清会话数 |

### 9.3 弹性控制

| 机制 | 配置 |
|------|------|
| 限流 | per-user: 100, per-tenant: 500, per-application: 1000 |
| 熔断 | 按能力级别，连续失败触发 |
| 舱壁 | maxConcurrent 按能力配置 |
| 超时预算 | 逐层递减：总超时 > LLM超时 > Provider超时 |

### 9.4 配置参考

```yaml
gateway:
  environment: ${GATEWAY_ENV:production}
  max-request-size-bytes: 65536
  default-timeout-ms: 15000
  llm:
    endpoint: ${LLM_ENDPOINT}
    api-key: ${LLM_API_KEY}
    model: ${LLM_MODEL:gpt-4}
    temperature: 0.1
    max-tokens: 4096
  audit:
    batch-size: 50
    batch-wait-millis: 5
    queue-capacity: 10000
  rate-limits:
    per-user: 100
    per-tenant: 500
    llm-concurrent: 10
    provider-concurrent: 50
```

---

## 10. 常见问题

### Q1: 为什么 LLM 不能直接调用接口？

网关的核心安全假设是"模型不是信任边界"。如果 LLM 直接调用接口，它需要知道接口地址、参数格式、认证信息——这些都是敏感配置。网关将 LLM 的角色压缩为"在候选中选择 + 提取参数"，所有安全决策由确定性代码完成。

### Q2: 为什么需要短别名？

短别名（`cap_<hash>`）隔离了模型与真实能力标识。模型无法通过记忆 capabilityId 来绕过候选集限制，也无法猜测未暴露给它的接口名称。

### Q3: 为什么写操作需要二阶段？

写操作有副作用且不可逆。二阶段协议确保：
- 用户明确确认后才执行
- 幂等键防止重复提交
- 操作状态可追溯
- 过期自动取消

### Q4: 如何新增一个能力？

1. 编写 Manifest YAML（参考 8.3 节）
2. 本地校验通过
3. 通过管理 API 导入
4. 等待 10 步校验通过
5. 确认
6. 发布

### Q5: Provider 接口变更了怎么办？

- 发布前：兼容性测试会检测签名变化，阻止发布
- 运行时：调用失败触发熔断，自动暂停建议
- 定期巡检：漂移检测任务对比协议签名摘要

### Q6: 首期支持哪些协议？

首期仅支持 **Dubbo GenericService 泛化调用**。REST 和 gRPC 适配器已预留模块，按演进计划实现。

### Q7: 如何调试路由问题？

1. 检查 Manifest 语义描述是否覆盖用户表达方式
2. 检查 BM25 检索是否召回正确能力（日志中有候选列表）
3. 检查 LLM 返回的 decision 和 alias
4. 检查 Schema 校验是否通过
5. 查看审计记录中的三检查点
