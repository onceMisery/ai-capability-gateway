# 真实端到端 Demo

本目录是项目唯一的端到端演示入口。它复用现有的 `test-provider`、注解处理器、Manifest CLI 和 `gateway-bootstrap`，不复制 Provider 方法、Manifest 规则或网关业务逻辑。

## 演示目标

Demo 使用订单查询能力 `order.detail.query`，完整展示两条链路：

1. `offline`：编译 Provider，读取注解处理器生成的 `capabilities.json`，合并治理配置和环境 Profile，生成并校验 Manifest Draft。
2. `runtime`：在 `offline` 基础上启动 PostgreSQL、Redis、Nacos、独立 Dubbo Provider 和网关，调用真实管理 API 完成导入、校验、审批、发布，再调用运行时 API 验证 Provider 返回值、字段投影和客户姓名脱敏。

运行时 Demo 使用开发环境 stub 认证（令牌为 `demo-token`），仅用于本地演示，禁止用于生产环境。

## 前置条件

- JDK 21（项目最低版本为 17）。
- Maven 3.9+。
- 仅运行 `offline` 时不需要 Docker、PostgreSQL、Redis、Nacos。
- 运行 `runtime` 时需要 Docker Desktop、Docker Compose v2、`curl` 和 `jq`（Windows PowerShell 版本不依赖 `jq`）。

## 运行方式

在仓库根目录执行：

```bash
# Linux/macOS/Git Bash
bash gateway-example/demo/demo.sh offline
bash gateway-example/demo/demo.sh runtime

# Windows PowerShell
.\gateway-example\demo\demo.ps1 -Mode offline
.\gateway-example\demo\demo.ps1 -Mode runtime
```

`offline` 生成的文件位于 `gateway-example/demo/.work/`：

- `manifests/order.detail.query.json`：待审核 Draft；
- `generation-report.json`：生成结果和失败项。

runtime 默认使用以下本机端口：网关 `8080`、Provider HTTP `8081`、Dubbo `20880`、PostgreSQL `5432`、Redis `6379`、Nacos `8848`。端口冲突时可通过环境变量覆盖，例如：

```powershell
$env:DEMO_GATEWAY_PORT = '18080'
.\gateway-example\demo\demo.ps1 -Mode runtime
```

Linux/macOS 可设置 `DEMO_GATEWAY_PORT`、`DEMO_POSTGRES_PORT`、`DEMO_REDIS_PORT`、`DEMO_NACOS_PORT`、`DEMO_PROVIDER_HTTP_PORT` 和 `DEMO_PROVIDER_DUBBO_PORT`。脚本会把 Compose 端口映射到这些端口，容器内服务地址不变。

runtime 默认在结束后执行 `docker compose down -v`，删除本 Demo 创建的容器和数据库卷。需要保留服务排查日志时：

```powershell
.\gateway-example\demo\demo.ps1 -Mode runtime -KeepServices
```

```bash
KEEP_SERVICES=true bash gateway-example/demo/demo.sh runtime
```

## runtime 验证内容

脚本对每一步都检查 HTTP 响应状态：

1. `POST /admin/v1/manifests:import` 返回 `IMPORTED`；
2. `POST /admin/v1/capabilities/order.detail.query/versions/1.0.0:validate` 返回有效状态；
3. `POST ...:approve` 返回 `APPROVED`；
4. `POST /admin/v1/releases:publish` 只发布本 Demo 的订单能力并返回 `PUBLISHED`；
5. `POST /api/v1/tools/order.detail.query:invoke` 返回 `COMPLETED`，`data.data.orderNo` 必须是 `DEMO-1001`；
6. `data.data.customerName` 不能等于 Provider 原始姓名 `Test Customer`，以证明投影和脱敏链路确实执行。

运行时响应保留网关治理 envelope 和 Provider 业务 envelope 两层结构，例如：

```json
{
  "status": "COMPLETED",
  "snapshotVersion": 1,
  "data": {
    "success": true,
    "data": {
      "orderNo": "DEMO-1001",
      "customerName": "Te*********er"
    }
  }
}
```

脚本按该实际契约读取 `data.data`，不会把治理 envelope 误当作业务对象。

Provider 的实现是确定性的：普通订单返回 `PAID` 和金额，`TIMEOUT`、`ERROR`、`LARGE` 可用于后续手工验证超时、异常和响应大小限制。Demo 默认只使用普通订单，避免把故障场景误认为成功路径。

## 失败排查

- `offline` 找不到 `capabilities.json`：先确认 Maven 使用了 JDK 17+，并检查 `gateway-example/test-provider/target/classes/META-INF/ai-gateway/`。
- Manifest 生成失败：查看 `.work/generation-report.json`，通常是治理策略、Profile 或 Schema 资源不完整。
- runtime 网关无法启动：使用 `docker compose -f gateway-example/demo/docker-compose.yml logs gateway` 查看 Flyway、数据库或 Dubbo 注册错误。
- runtime 调用返回 `CAPABILITY_UNAVAILABLE`：先查看 Provider 日志，再确认 Nacos 健康状态和 Provider 是否已注册 `OrderQueryApi`。
- 使用共享 Redis 和隔离 PostgreSQL Schema 时，二者必须指向同一套快照基线。若 Redis 缓存的
  `snapshotVersion` 在当前 Schema 的 `catalog_snapshot` 中不存在，审计外键会按 Fail Closed
  语义拒绝调用。E2E 应同时隔离缓存环境键与数据库 Schema，或复用同一套已发布基线。
- Docker 不可用时只能报告 `offline` 验证结果，不能把 runtime 标记为已验证。

## 设计边界

该 Demo 是编排和验收工具，不是新的生产部署模板。它刻意保留现有 Provider 方法签名和数据库迁移脚本，不修改 `application-local.yml`，所有运行时敏感配置通过环境变量或 Compose 注入。生产部署仍必须关闭 stub 认证，使用真实认证、授权、密钥和基础设施配置。
