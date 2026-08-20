# Gateway Example — AI 能力网关使用示例

> 权威端到端演示入口为 [`demo/README.md`](demo/README.md)，本文件只保留模块说明和扩展场景参考。

本聚合模块（pom）提供 AI 能力网关的端到端演示套件，包含两个子模块：

- **`test-client`**：HTTP API 客户端、自然语言查询演示、管理工作流演示、Manifest 编写指南以及 Manifest 结构验证测试。
- **`test-provider`**：独立 Dubbo 服务，提供 `OrderQueryApi` / `PurchaseListApi` 的模拟实现，注册到 Nacos，用于网关兼容性测试（§21.2）。

## 模块结构

```
gateway-example/
├── pom.xml                                    # 聚合 pom
├── test-client/                               # 客户端与演示程序
│   ├── pom.xml
│   ├── src/main/java/com/ai/gateway/example/
│   │   ├── client/GatewayApiClient.java       # HTTP API 客户端（纯 JDK HttpClient）
│   │   ├── demo/
│   │   │   ├── NaturalLanguageQueryDemo.java  # 自然语言查询演示
│   │   │   └── AdminWorkflowDemo.java         # 控制面管理工作流演示
│   │   └── manifest/
│   │       └── ManifestAuthoringGuide.java    # Manifest 编写指南（可执行文档）
│   ├── src/main/resources/manifests/
│   │   ├── order-detail-query.yaml            # 示例：订单详情查询（READ_ONLY）
│   │   └── purchase-list-query.yaml           # 示例：采购列表查询（READ_ONLY）
│   └── src/test/java/
│       └── GatewayExampleTest.java            # Manifest 结构验证测试（13 个用例）
└── test-provider/                             # 独立 Dubbo Provider（§21.2）
    ├── pom.xml
    ├── src/main/java/com/ai/gateway/testprovider/
    │   ├── TestProviderApplication.java       # Spring Boot 启动类
    │   ├── OrderQueryApi.java / Impl.java     # 订单查询 Dubbo 服务
    │   └── PurchaseListApi.java / Impl.java   # 采购列表 Dubbo 服务
    └── src/main/resources/application.yml
```

## 环境准备

### 前置条件

| 依赖 | 版本 | 说明 |
|------|------|------|
| JDK | 17+ | 生产推荐 21 LTS |
| Maven | 3.9+ | 构建工具 |
| PostgreSQL | 14+ | 网关数据库（运行网关时需要） |
| LLM 服务 | — | OpenAI 兼容 API（运行网关时需要） |
| Nacos | 2.x | Dubbo 注册中心（运行网关时需要） |

### 编译

```bash
cd ai-capability-gateway
mvn clean install -DskipTests
```

### 编译 test-client 和 test-provider

```bash
mvn -pl gateway-example/test-provider,gateway-example/test-client -am compile
```

## 端到端运行（唯一入口）

完整流程由 `gateway-example/demo` 统一编排，不再维护另一套手工启动和 curl 命令。

```powershell
.\gateway-example\demo\demo.ps1 -Mode offline
.\gateway-example\demo\demo.ps1 -Mode runtime
```

```bash
bash gateway-example/demo/demo.sh offline
bash gateway-example/demo/demo.sh runtime
```

详细前置条件、端口覆盖、断言内容、清理方式和故障排查见 [`demo/README.md`](demo/README.md)。

### Test Provider 测试场景

`OrderQueryApi` 支持以下特殊订单号用于测试：

| 订单号 | 行为 | 用途 |
|--------|------|------|
| `SO202607210001` | 正常返回订单数据 | 正常流程验证 |
| `TIMEOUT` | 休眠 10 秒 | 超时/熔断测试 |
| `ERROR` | 抛出 RuntimeException | 异常处理测试 |
| `LARGE` | 返回 5000 条记录 | 响应大小限制测试 |
| 空/缺失 | 返回 code=400 | 业务校验测试 |

### 数据流全链路

```
用户: "查询订单 SO202607210001"
  │
  v
网关认证 (Stub: 任意 token 通过)
  │
  v
BM25 检索 → 命中 order.detail.query
  │
  v
LLM 路由 → SELECT cap_xxx, arguments: {orderNo: "SO202607210001"}
  │
  v
Schema 校验 → 通过 (pattern: ^SO[0-9]{12}$)
  │
  v
参数绑定 → [orgId=10001(PRINCIPAL), {orderNo: "SO202607210001"}(MODEL)]
  │
  v
Dubbo GenericService.$invoke("query", [Long, Map], args)
  │
  v
Test Provider 返回: {code: "200", value: {orderNo, status, amount, customerName}, message}
  │
  v
结果治理 → Envelope 解包 → projection → 脱敏(customerName) → publicSchema 校验
  │
  v
响应: {status: "COMPLETED", data: {orderNo, status, amount, customerName: "Test C*******"}}
```

---

## 运行演示程序（test-client 子模块）

> 以下演示程序均位于 **`gateway-example/test-client`** 子模块。
> 认证相关配置详见下文“认证集成（Authentication Integration）”章节。

### 1. 自然语言查询演示

展示运行面（Runtime Plane）的完整查询流程。

**前提**：网关已启动且至少有一个 PUBLISHED 状态的能力。

```bash
# 使用默认配置（localhost:8080, demo-jwt-token）
java -cp gateway-example/test-client/target/classes:gateway-domain/target/classes \
  com.ai.gateway.example.demo.NaturalLanguageQueryDemo

# 指定网关地址和 Token
java -cp ... com.ai.gateway.example.demo.NaturalLanguageQueryDemo \
  http://gateway-host:8080 my-jwt-token
```

**演示内容**：

| 示例 | 输入 | 预期结果 |
|------|------|---------|
| 直接查询 | "查询订单 SO202607210001" | COMPLETED + 订单数据 |
| 澄清流程 | "帮我查询一下订单" | CLARIFICATION_REQUIRED → 补充参数 → COMPLETED |
| 无匹配 | "今天天气怎么样" | NO_MATCH |
| 错误处理 | 无效 Token | ERROR (AUTHENTICATION_FAILED) |

**预期输出示例**：

```
======================================================================
AI Capability Gateway — Natural Language Query Demo
======================================================================
Gateway URL: http://localhost:8080

--- Example 1: Direct Query (COMPLETED) ---
  Status: COMPLETED
  Data: {orderNo=SO202607210001, status=PAID, amount=1299.00}
  Summary: Capability order.detail.query executed successfully
  Snapshot Version: 3

--- Example 2: Clarification Flow ---
  Step 1: Sending ambiguous query...
  Status: CLARIFICATION_REQUIRED
  Question: 请提供您要查询的订单号
  InteractionId: a1b2c3d4-...
  Step 2: Continuing with orderNo...
  Status: COMPLETED
  Data: {orderNo=SO202607210002, status=SHIPPED}

--- Example 3: No Match ---
  Status: NO_MATCH
  Message: No capability matched your query

--- Example 4: Error Handling ---
  [ERROR] Authentication failed: invalid token

======================================================================
Demo complete.
======================================================================
```

### 2. 管理工作流演示

展示控制面（Control Plane）的完整能力生命周期管理。

**前提**：网关已启动，使用具有管理权限的 Token。

```bash
java -cp ... com.ai.gateway.example.demo.AdminWorkflowDemo \
  http://localhost:8080 admin-jwt-token
```

**演示内容**：

| 步骤 | 操作 | 说明 |
|------|------|------|
| 1 | Import | 导入 Manifest YAML，触发 10 步校验流水线 |
| 2 | Validate | 查看校验报告（Schema/兼容性/安全） |
| 3 | Approve | 审批通过，状态变为 APPROVED |
| 4 | Publish | 发布到 production，生成不可变快照 |
| 5 | Suspend | 紧急停用（可选） |
| 6 | Rollback | 回滚到上一个快照版本（可选） |

**生命周期状态机**：

```
DRAFT → IMPORTED → VALIDATED → APPROVED → PUBLISHED
                                              ↓
                                         SUSPENDED
```

### 3. Manifest 编写指南

可执行的文档程序，打印 Manifest 编写规范并验证示例文件。

```bash
java -cp ... com.ai.gateway.example.manifest.ManifestAuthoringGuide
```

**输出内容**：
- Manifest 顶层结构说明（apiVersion, kind, metadata, spec）
- 参数绑定规则（MODEL / PRINCIPAL / CONSTANT / SYSTEM）
- 安全约束（additionalProperties: false, 保留字段拒绝）
- 示例 Manifest 校验结果

## GatewayApiClient 使用指南

`GatewayApiClient` 是一个轻量级 HTTP 客户端，仅依赖 JDK `HttpClient` 和 Jackson（位于 `test-client` 子模块）。

### 初始化

```java
GatewayApiClient client = new GatewayApiClient(
    "http://localhost:8080",   // 网关地址
    "my-jwt-bearer-token"      // Bearer Token
);
```

### 自然语言查询

```java
Map<String, Object> result = client.naturalLanguageQuery(
    "查询订单 SO202607210001",  // 自然语言文本
    "zh-CN"                     // 语言区域
);

String status = (String) result.get("status");
switch (status) {
    case "COMPLETED" -> System.out.println("数据: " + result.get("data"));
    case "CLARIFICATION_REQUIRED" -> {
        String interactionId = (String) result.get("interactionId");
        String question = (String) result.get("clarificationQuestion");
        // 继续澄清...
    }
    case "NO_MATCH" -> System.out.println("无匹配能力");
    case "ERROR" -> System.out.println("错误: " + result.get("errorCode"));
}
```

### 澄清会话

```java
Map<String, Object> result = client.continueClarification(
    "a1b2c3d4-interaction-id",  // 上一步返回的 interactionId
    "订单号是 SO202607210001"    // 补充信息
);
```

### 写操作（二阶段协议）

```java
// Phase 1: Prepare
Map<String, Object> prepared = client.prepareAction("取消订单 SO202607210001");
String operationId = (String) prepared.get("operationId");
String confirmToken = (String) prepared.get("confirmationToken");

// Phase 2: Confirm
Map<String, Object> confirmed = client.confirmOperation(operationId, confirmToken);
System.out.println("操作状态: " + confirmed.get("state"));
```

### 管理操作

```java
// 导入 Manifest
String yaml = Files.readString(Path.of("order-detail-query.yaml"));
Map<String, Object> imported = client.importManifest(yaml);

// 审批
client.approveCapability("order.detail.query", "1.0.0");

// 发布
client.publishSnapshot("production");
```

### 错误处理

所有 API 方法在失败时抛出 `GatewayApiClient.GatewayApiException`：

```java
try {
    client.naturalLanguageQuery("查询订单", "zh-CN");
} catch (GatewayApiClient.GatewayApiException e) {
    System.err.println("HTTP " + e.getStatusCode() + ": " + e.getMessage());
}
```

## 认证集成（Authentication Integration）

网关通过可插拔开关 `gateway.auth.provider` 支持两种认证实现。`gateway-example/test-client` 内置对应的客户端集成方式。

| 开关值 | 网关实现 | 客户端对应方式 |
|--------|---------|---------------|
| `stub`（默认） | `StubAuthConfiguration` — 任意非空 token 通过 | Demo 默认模式，token = `demo-jwt-token` |
| `sa-token` | `SaTokenAuthConfiguration` — HS256 JWT 验签 | Demo `sa-token` 模式，使用 `SaTokenIssuer` 签发 |

### 端到端认证链路

```
客户端（Demo / curl / GatewayApiClient）
  │  Authorization: Bearer <token>
  v
Spring MVC (@RequestHeader AUTH_HEADER)
  │
  v
RequestContextFactory → RequestContext(headers)
  │
  v
AuthenticationPort.authenticate(context)        ← 插件选择
  │  ├─ stub     → 剥离 Bearer 前缀，直接构造 Principal
  │  └─ sa-token → SaJwtUtil.getPayloadsNotCheck(token, loginType, keyt)
  │                 验签 → 提取 loginId/orgId/roles/permissions
  v
Principal (subject, orgId, roles, permissions, authMethod)
  │
  v
AuthorizationPort.authorizeExecution(principal, capabilityId, version)
  │
  v
Use Case（NaturalLanguageQuery / Operation / Admin）
```

### 环境变量一览

| 变量名 | 用途 | 默认值 | 适用模式 |
|--------|------|--------|---------|
| `GATEWAY_AUTH_PROVIDER` | 网关侧认证插件（`stub` \| `sa-token`） | `stub` | 网关 |
| `GATEWAY_AUTH_JWT_SECRET` | 共享 HS256 密钥（必须与网关一致） | 空 | sa-token |
| `GATEWAY_AUTH_LOGIN_TYPE` | Sa-Token 登录类型 | `login` | sa-token |
| `GATEWAY_AUTH_LOGIN_ID` | Sa-Token 登录主体 | `demo-user` | sa-token |
| `GATEWAY_AUTH_TIMEOUT_SECONDS` | Token 有效时长 | `7200` | sa-token |
| `GATEWAY_AUTH_MODE` | Demo 认证模式（`stub` \| `sa-token` \| `custom`） | `stub` | Demo |
| `GATEWAY_AUTH_TOKEN` | Demo 自定义 token（custom 模式回退） | — | Demo |

### 场景 1：Stub 模式（默认，开箱即用）

网关：`gateway.auth.provider=stub`（默认）
客户端：`GATEWAY_AUTH_MODE=stub`（默认）

```bash
# 网关默认启动即为 stub
mvn -pl gateway-bootstrap spring-boot:run

# Demo 默认即以 stub 模式连接
java -cp gateway-example/test-client/target/classes:gateway-domain/target/classes \
  com.ai.gateway.example.demo.NaturalLanguageQueryDemo
```

预期输出：

```
======================================================================
AI Capability Gateway — Natural Language Query Demo
======================================================================
Gateway URL : http://localhost:8080
Auth mode   : stub (gateway.auth.provider=stub)
```

### 场景 2：Sa-Token 模式（真实 JWT 验签）

网关：`gateway.auth.provider=sa-token`，配置 `GATEWAY_AUTH_JWT_SECRET`。
客户端：`GATEWAY_AUTH_MODE=sa-token`，共享同一 secret。

```bash
# 1. 网关以 sa-token 模式启动（同一密钥）
GATEWAY_AUTH_PROVIDER=sa-token \
GATEWAY_AUTH_JWT_SECRET=please-change-me-to-a-random-32-byte-secret \
  mvn -pl gateway-bootstrap spring-boot:run

# 2. Demo 以 sa-token 模式签发 JWT 并调用
GATEWAY_AUTH_MODE=sa-token \
GATEWAY_AUTH_JWT_SECRET=please-change-me-to-a-random-32-byte-secret \
GATEWAY_AUTH_LOGIN_ID=demo-user \
GATEWAY_AUTH_LOGIN_TYPE=login \
GATEWAY_AUTH_TIMEOUT_SECONDS=7200 \
  java -cp gateway-example/test-client/target/classes:gateway-domain/target/classes \
    com.ai.gateway.example.demo.NaturalLanguageQueryDemo
```

预期输出：

```
======================================================================
AI Capability Gateway — Natural Language Query Demo
======================================================================
Gateway URL : http://localhost:8080
Auth mode   : sa-token (loginId=demo-user, loginType=login, ttl=7200s)
```

此时 Demo 内部流程：
1. `SaTokenIssuer.issue(...)` 调用 `SaJwtUtil.createToken(loginType, loginId, null, timeoutSeconds, {orgId,roles,permissions}, secret)`，生成标准 Sa-Token JWT。
2. `GatewayApiClient` 拼装 `Authorization: Bearer <jwt>`。
3. 网关 `SaTokenAuthenticationAdapter` 调用 `SaJwtUtil.getPayloadsNotCheck(token, "login", secret)` 验签通过，构造 `Principal(demo-user, 10001, [user], [*], SA_TOKEN_JWT)`。

### 场景 3：Custom 模式（对接既有 IdP / 第三方 JWT）

适用于对接 Keycloak、自研 IdP 等签发的 JWT；网关需切换为能解析对应格式的 `AuthenticationPort`。

```bash
GATEWAY_AUTH_MODE=custom \
GATEWAY_AUTH_TOKEN="<由 IdP 签发的完整 JWT>" \
  java -cp ... com.ai.gateway.example.demo.NaturalLanguageQueryDemo
```

### SaTokenIssuer 编程式用法

`SaTokenIssuer` 是可复用的 token 签发器（依赖 `sa-token-jwt` → `hutool-jwt`），可在业务代码或测试中直接使用：

```java
SaTokenIssuer issuer = new SaTokenIssuer("shared-secret");

// 最简：subject + 2h 默认超时
String jwt = issuer.issue("user-123");

// 带组织/角色/权限
String jwt = issuer.issue("user-123", 10001L,
        List.of("user", "analyst"),
        List.of("order.read", "order.write"),
        3600L);

// 完全自定义 claims
String jwt = issuer.issue("user-123", Map.of(
        "orgId", 10001L,
        "tenantId", "acme",
        "department", "finance"), 7200L);
```

### 认证失败排查

| 现象 | 可能原因 | 处理 |
|------|---------|------|
| `AUTHENTICATION_FAILED: no credential found` | 客户端未携带 `Authorization` 头 | 确认 `GatewayApiClient` 已构造 `Bearer ...` |
| `AUTHENTICATION_FAILED: invalid signature` | Sa-Token 模式密钥不一致 | 比对网关 `GATEWAY_AUTH_JWT_SECRET` 与客户端 `GATEWAY_AUTH_JWT_SECRET` |
| `AUTHENTICATION_FAILED: loginType mismatch` | 网关配置 `login-type` 与签发端不一致 | 对齐 `GATEWAY_AUTH_LOGIN_TYPE` 与 `gateway.auth.sa-token.login-type` |
| `AUTHENTICATION_FAILED: token expired` | JWT 超时（SaJwtUtil 的 `eff` 字段过期） | 增大 `GATEWAY_AUTH_TIMEOUT_SECONDS` 或重新签发 |
| `PERMISSION_DENIED` | 鉴权拒绝 | 检查 `AuthorizationPort.authorizeExecution` 的 ACL 配置 |

## 示例 Manifest 说明

### order-detail-query.yaml

将 `com.example.order.api.OrderQueryApi#query` 转换为自然语言能力。

| 字段 | 值 | 说明 |
|------|-----|------|
| metadata.id | order.detail.query | 领域.资源.动作 命名 |
| spec.risk | READ_ONLY | 只读，可立即执行 |
| inputSchema | orderNo (必填) | 正则 `^SO[0-9]{12}$` |
| 参数绑定 | orderNo=MODEL, orgId=PRINCIPAL | 模型提取 + 身份注入 |
| 协议 | Dubbo GenericService | 泛化调用，不加载 API JAR |
| 输出 | envelope + projection + redaction | 解包 → 投影 → 脱敏 |

### purchase-list-query.yaml

将 `com.example.purchase.api.PurchaseQueryApi#listByCondition` 转换为自然语言能力。

| 字段 | 值 | 说明 |
|------|-----|------|
| metadata.id | purchase.list.query | 列表查询 |
| spec.risk | READ_ONLY | 只读 |
| inputSchema | status (可选), startDate (可选) | 复合绑定示例 |
| 类型转换 | ISO_DATE_TO_EPOCH_MILLIS | 受控转换器 |

## 运行测试

```bash
# 仅运行 test-client 子模块（含 13 个 Manifest 结构验证用例）
mvn test -pl gateway-example/test-client

# 运行整个 gateway-example 聚合模块（含所有子模块）
mvn test -pl gateway-example
```

test-client 测试覆盖（13 个用例）：
- YAML 语法有效性
- 必填字段完整性
- Input Schema 安全约束（additionalProperties: false）
- 输出合约结构
- 弹性策略配置
- 语义描述（positive/negative/synonyms）
- 序列化白名单（仅 hessian2/fastjson2）

## 注意事项

1. **Test Provider 是独立 Dubbo 服务**：通过 `mvn -pl gateway-example/test-provider spring-boot:run` 启动；网关通过 Nacos 发现它。
2. **演示程序需要网关运行**：`NaturalLanguageQueryDemo` 和 `AdminWorkflowDemo` 需要网关服务已启动。如果网关未运行，会抛出连接异常。
3. **ManifestAuthoringGuide 可离线运行**：该程序仅读取 classpath 中的 YAML 文件进行结构验证，不需要网关服务。
4. **Token 是占位符**：默认 Token（`demo-jwt-token`）仅在网关使用 Stub AuthenticationPort 时有效。生产环境需替换为真实 JWT（Sa-Token 模式下使用 `SaTokenIssuer` 签发，详见“认证集成”章节）。
5. **示例 Manifest 中的 Provider 是虚构的**：`com.example.order.api.OrderQueryApi` 不存在于 classpath 中，仅用于展示 Manifest 结构；真实 Dubbo 实现由 `test-provider` 子模块提供。
