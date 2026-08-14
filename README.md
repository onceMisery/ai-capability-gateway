# AI Capability Gateway

AI 能力网关将经过治理的微服务 API 转换为可被自然语言发现和调用的"能力"。网关在用户权限范围内检索候选能力，由大语言模型完成能力选择和业务参数提取，最终通过确定性执行链路完成认证、授权、参数注入、Schema 校验、协议调用、结果脱敏和审计。

## 核心设计理念

- **模型不是信任边界**：LLM 输出始终是不可信输入，只能在网关给出的候选集合中选择能力并提取参数。所有协议配置、租户、身份、授权策略均由确定性系统掌控。
- **先授权，后暴露**：候选检索前按 Principal 过滤能力，未授权能力的名称和存在性不暴露给模型或用户。
- **控制面与运行面分离**：控制面负责导入、校验、确认、发布和回滚；运行面只读取已发布的不可变目录快照。
- **最小能力面**：一个 Capability 只表达一个清晰的业务动作，输入只包含完成该动作所需的最少业务字段。

## 主要功能

| 功能 | 说明 |
|------|------|
| Capability Manifest 管理 | 导入、校验（10 步流水线）、确认、发布、停用、回滚 |
| 自然语言路由 | BM25 候选检索 + LLM 受限选择 + 确定性校验 |
| 确定性执行链路 | 参数绑定、Principal 注入、协议调用、结果脱敏 |
| 多协议适配 | Dubbo GenericService 泛化调用（首期），REST/gRPC（演进） |
| 写操作二阶段协议 | Prepare/Confirm/Status 状态机，幂等键，CAS 防重 |
| 审计与可观测性 | 三检查点持久化、Micro-batching 分组提交、Outbox 导出 |
| 弹性控制 | 限流、熔断、舱壁隔离、超时预算逐层递减 |
| 澄清会话 | 多轮对话、意图跳出识别、会话过期管理 |

## 快速开始

### 环境要求

- JDK 17+（生产推荐 JDK 21 LTS）
- Maven 3.9+
- PostgreSQL 15+
- Nacos 注册中心（Dubbo 服务发现）
- LLM API 端点（OpenAI 兼容接口）

### 构建

```bash
# 独立构建（不依赖父仓库其他模块）
mvn -f ai-capability-gateway/pom.xml clean verify

# 查看依赖树（确认无内部依赖）
mvn -f ai-capability-gateway/pom.xml dependency:tree
```

### 配置

复制并修改 `gateway-bootstrap/src/main/resources/application.yml`：

```yaml
# 必需环境变量
DB_URL=jdbc:postgresql://localhost:5432/ai_gateway
DB_USERNAME=gateway
DB_PASSWORD=your-password
LLM_ENDPOINT=https://api.openai.com/v1/chat/completions
LLM_API_KEY=sk-xxx
NACOS_ADDRESS=localhost
```

### 启动

```bash
# 启动网关
mvn -pl gateway-bootstrap spring-boot:run

# 启动测试 Provider（独立终端）
mvn -pl gateway-test-provider spring-boot:run
```

### 基本使用

**自然语言查询：**

```bash
curl -X POST http://localhost:8080/api/v1/natural-language/queries \
  -H "Authorization: Bearer <your-token>" \
  -H "Content-Type: application/json" \
  -d '{"requestId": "req-001", "text": "查询订单 SO202607210001", "locale": "zh-CN"}'
```

**响应示例（成功）：**

```json
{
  "status": "COMPLETED",
  "data": {"orderNo": "SO202607210001", "status": "PAID", "amount": 299.00},
  "summary": "订单 SO202607210001 当前状态为已支付。",
  "snapshotVersion": 103
}
```

**响应示例（需要澄清）：**

```json
{
  "status": "CLARIFICATION_REQUIRED",
  "question": "请提供要查询的订单号",
  "interactionId": "int_01J..."
}
```

**管理操作：**

```bash
# 导入 Manifest
curl -X POST http://localhost:8080/admin/v1/manifests:import \
  -H "Content-Type: application/json" \
  -d @gateway-example/src/main/resources/manifests/order-detail-query.yaml

# 发布快照
curl -X POST http://localhost:8080/admin/v1/releases:publish \
  -H "Content-Type: application/json" \
  -d '{"environment": "production"}'
```

## 项目结构

```
ai-capability-gateway/
├── gateway-domain/              # 纯领域模型和端口（零框架依赖）
│   ├── model/                   #   38 个值对象、枚举、记录
│   ├── port/                    #   22 个端口接口（外部服务抽象）
│   └── service/                 #   10 个领域服务（参数绑定、结果治理等）
├── gateway-application/         # 用例编排（纯 Java，无 Spring 注解）
│   ├── controlplane/            #   控制面：导入、校验、确认、发布、回滚
│   ├── runtime/                 #   运行面：NL 路由、确定性执行、澄清
│   ├── operation/               #   写操作：Prepare/Confirm/Status
│   ├── catalog/                 #   内存快照管理、BM25 检索、漂移检测
│   └── resilience/              #   限流、熔断、舱壁、故障策略
├── gateway-adapter-web/         # REST API（Spring Web）
├── gateway-adapter-postgresql/  # 持久化（Flyway + JDBC + Audit Micro-batching）
├── gateway-adapter-llm-http/    # LLM 结构化调用
├── gateway-adapter-dubbo/       # Dubbo 泛化调用
├── gateway-adapter-rest/        # REST 适配器（演进骨架）
├── gateway-adapter-grpc/        # gRPC 适配器（演进骨架）
├── gateway-bootstrap/           # Spring Boot 启动 + Bean 装配
├── gateway-contract-schema/     # Manifest JSON Schema (2020-12)
├── gateway-manifest-cli/        # 离线 Manifest 生成/校验工具
├── gateway-test-provider/       # 独立 Dubbo 测试服务
└── gateway-example/             # 使用示例和演示
```

### 依赖方向

```
adapter / bootstrap  →  application  →  domain
```

- `gateway-domain`：零框架依赖，仅 JDK 标准库
- `gateway-application`：零 Spring 依赖，纯 Java 构造器注入
- Spring 仅用于适配层（adapter）和启动层（bootstrap）

## 架构概述

```
                         ┌──────────────────────┐
Manifest / OpenAPI /     │      控制面 API      │
Proto / API JAR(离线) ──>│ 导入-校验-确认-发布  │
                         └──────────┬───────────┘
                                    │ immutable snapshot
                                    v
┌────────┐  JWT/OIDC  ┌──────────────────────────────────────┐
│ Client │───────────>│              运行面                  │
└────────┘            │ 认证 -> 授权过滤 -> BM25 候选检索   │
                      │ -> LLM 选择/提参 -> 确定性校验       │
                      │ -> 参数绑定 -> 协议适配 -> 结果治理  │
                      └──────┬───────────────┬───────────────┘
                             v               v
                      ┌────────────┐   ┌──────────────┐
                      │ LLM 服务   │   │ 协议 Provider│
                      └────────────┘   └──────────────┘
```

### 自然语言路由流水线

1. 认证并构造 Principal
2. 固定目录快照（不可变）
3. 权限和环境预过滤
4. 文本规范化
5. BM25 Top-K 检索
6. 阈值/歧义初筛
7. 构造请求内短别名候选（`cap_<hash>`）
8. LLM 在候选中选择能力并提取 MODEL 参数
9. 选择合法性与 Schema 校验
10. 执行前再次授权
11. 确定性调用或发起澄清

### 安全边界

- LLM 不接收协议 Binding、服务地址、接口类名、租户、用户身份
- orgId 等可信参数仅从 Principal 注入，不接受用户/模型覆盖
- 审计 Fail Closed：终态未持久化则不返回数据
- 序列化白名单：仅 hessian2、fastjson2（社区发行版）
- Enforcer 禁止所有内部基础设施依赖

## Capability Manifest 示例

```yaml
apiVersion: gateway.ai/v1
kind: Capability
metadata:
  id: order.detail.query
  version: 1.0.0
  owner:
    team: order-platform
    contact: order-platform@example.com
spec:
  displayName: 查询订单详情
  description: 根据订单号查询当前组织下的单个订单详情。
  risk: READ_ONLY
  inputSchema:
    type: object
    additionalProperties: false
    properties:
      orderNo:
        type: string
        pattern: "^SO[0-9]{12}$"
    required: [orderNo]
  invocation:
    protocol: DUBBO
    registryRef: nacos-main
    interfaceName: com.example.order.api.OrderQueryApi
    method: query
    serialization: fastjson2
    arguments:
      - position: 0
        name: orgId
        source: PRINCIPAL
        sourcePath: /orgId
      - position: 1
        name: request
        source: MODEL
        sourcePath: /
  output:
    mode: ENVELOPE
    envelope:
      codePath: /code
      successValues: ["200"]
      dataPath: /value
    redactions:
      - path: /customerName
        method: PARTIAL_MASK
  resilience:
    timeoutMs: 1500
    retries: 1
    maxConcurrent: 50
```

完整示例参见 `gateway-example/src/main/resources/manifests/`。

## 测试

```bash
# 运行全部单元测试
mvn test

# 仅运行领域层测试（含 ArchUnit 架构约束验证）
mvn test -pl gateway-domain

# 运行示例模块测试
mvn test -pl gateway-example
```



## 许可证

内部项目，保留所有权利。
