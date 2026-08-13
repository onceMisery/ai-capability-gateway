# AI 能力网关管理后台规格说明（Admin Console Spec）

> 版本：1.0-DRAFT
> 日期：2026-08-10
> 状态：待评审
> 适用对象：ai-capability-gateway 管理后台的设计与实现

---

## 1. 文档目的与范围

本文档定义 AI 能力网关（ai-capability-gateway）**管理后台**（Admin Console）的完整规格：产品能力、技术选型、认证集成链路、页面与交互设计、API 兼容层契约、数据模型扩展、里程碑与验收标准。

管理后台是网关的**控制面图形化入口**，覆盖以下六大基础管理能力：

| # | 能力域 | 说明 |
|---|--------|------|
| 1 | 认证集成 | 与网关认证系统集成，支持 stub / Sa-Token 两种模式，完整传递认证令牌链路 |
| 2 | 能力清单管理 | Manifest 导入、校验、审批、发布、停用、回滚的完整生命周期管理 |
| 3 | 快照管理 | 当前与历史快照查看、详情展示、发布与回滚操作 |
| 4 | 运行监控 | 网关运行状态、能力调用统计、错误日志与审计信息 |
| 5 | 权限管理 | 用户角色与权限配置、能力级访问控制、授权策略管理 |
| 6 | 系统配置 | 网关基础配置、限流规则（Sentinel）、缓存配置查看 |

**明确不在范围内**：
- 网关运行面的改造（自然语言查询链路不因管理后台变更）
- LLM 供应商管理（后续按需引入）
- 多租户自助服务门户（管理后台是运维工具，不面向终端用户自助开通）

---

## 2. 背景与约束

### 2.1 现有架构事实（截至 2026-08-10）

管理后台必须与以下已落地的网关能力兼容：

| 事实 | 说明 |
|------|------|
| 六边形架构 | adapter / bootstrap → application → domain，依赖单向，ArchUnit 强制校验 |
| 控制面 API | `gateway-adapter-web` 的 `AdminController`，前缀 `/admin/v1` |
| 运行面 API | `NaturalLanguageController`（前缀 `/api/v1/natural-language`）与 `OperationController` |
| 认证可插拔 | `gateway.auth.provider=stub`（默认）\| `sa-token`，由 `StubAuthConfiguration` / `SaTokenAuthConfiguration` 条件装配 |
| 授权可插拔 | `AuthorizationPort`：`filterVisibleCapabilities` / `authorizeExecution` / `authorizeAdmin`；`AdminAction` 枚举：IMPORT / APPROVE / PUBLISH / ROLLBACK / SUSPEND |
| 快照模型 | 不可变 `CatalogSnapshot`，`catalog_snapshot` + `catalog_snapshot_item` 表，单调递增 snapshot_version |
| 持久化 | PostgreSQL 15+ + Flyway（V1~V3 已存在） |
| 审计 | `audit_event` 表（三检查点）、`execution_record` 表；Micro-batching 写入；`GET /admin/v1/audits` 当前为 NOT_IMPLEMENTED 骨架 |
| 限流 | Sentinel Core 编程式 API，`gateway.ratelimit.provider=stub`（默认）\| `sentinel`；规则硬编码于 `SentinelRuleInitializer` |
| 缓存 | `gateway.cache.provider=stub`（默认）\| `redis`（Redisson + Caffeine L1） |
| 配置 | `gateway-bootstrap/src/main/resources/application.yml`，`gateway.*` 前缀 |

### 2.2 现有 API 事实清单（管理后台复用的基础）

| 方法 | 路径 | 说明 | 实现状态 |
|------|------|------|---------|
| POST | `/admin/v1/manifests:import` | 导入 Manifest（**JSON body**，`CapabilityManifest` 反序列化），触发 10 步校验 | ✅ 已有 |
| POST | `/admin/v1/capabilities/{id}/versions/{version}:validate` | 重新校验 | ✅ 已有 |
| POST | `/admin/v1/capabilities/{id}/versions/{version}:approve` | 审批（body：`{approver}`） | ✅ 已有 |
| POST | `/admin/v1/releases:publish` | 发布快照（body：`{environment}`） | ✅ 已有 |
| POST | `/admin/v1/releases:rollback` | 回滚（body：`{targetSnapshotVersion, environment}`） | ✅ 已有 |
| POST | `/admin/v1/capabilities/{id}:suspend` | 紧急停用（body：`{reason, operator}`） | ✅ 已有 |
| GET | `/admin/v1/releases/{snapshotVersion}` | 快照详情 | ✅ 已有 |
| GET | `/admin/v1/audits` | 审计查询 | 🟡 骨架（NOT_IMPLEMENTED） |
| POST | `/api/v1/natural-language/queries` | 自然语言查询 | ✅ 已有 |
| POST | `/api/v1/natural-language/interactions/{id}/messages` | 澄清会话 | ✅ 已有 |
| GET | `/health/readiness` | 就绪探针 | ✅ 已有 |
| GET | `/health/liveness` | 存活探针 | ✅ 已有 |
| GET | `/actuator/health` `/actuator/metrics` `/actuator/prometheus` | Spring Boot Actuator | ✅ 已有 |

> **重要差异提示**：`gateway-example/README.md` 中的 curl 示例使用 `POST /api/v1/admin/manifests`（YAML body），但实际 `AdminController` 的导入端点为 `POST /admin/v1/manifests:import`（JSON body）。管理后台**以实际代码为准**，采用 `/admin/v1/...` + JSON 契约。

### 2.3 安全模型约束（必须遵循）

1. **先授权，后暴露**：未授权能力的名称和存在性不暴露；管理后台页面与操作按钮必须按当前登录者权限动态渲染，且网关侧管理端点必须执行 `authorizeAdmin` 门禁（见 §5.5 差距项）。
2. **LLM 不是信任边界**：管理后台不得向 LLM 暴露协议配置；Manifest 详情页中的 invocation 配置（接口名、地址、序列化）仅对 admin 角色可见。
3. **敏感信息不落地前端**：JWT secret、数据库密码、LLM API Key 等一律不通过管理 API 返回；配置查看端点只返回非敏感项。
4. **管理操作可审计**：管理后台触发的所有变更操作沿用网关审计链路，界面提供审计查询入口。
5. **控制面与运行面分离**：管理后台只调用控制面 API 与查询类 API，不直接触碰运行面内部状态。

### 2.4 独立性约束

- 管理后台前端为独立 Node 工程，**不参与 Maven 构建**，不引入任何 `com.ec:*` 依赖，不违反网关 Enforcer 约束。
- 网关侧新增端点仍遵循 adapter → application → domain 依赖方向，新增 Port 定义在 `gateway-domain`。

---

## 3. 总体设计

### 3.1 形态与部署

管理后台为**前后端分离的单页应用（SPA）**：

```
┌────────────────────┐        ┌──────────────────────────────────────┐
│  管理后台前端 SPA    │  HTTPS │          AI 能力网关（Spring Boot）     │
│  gateway-admin-     │───────▶│  /admin/v1/*   控制面（扩展后）         │
│  console/           │ Bearer │  /api/v1/*     运行面（只读调用）       │
│  (Vue3 + Vite)      │  Token │  /admin/v1/console/auth/*  控制台认证   │
└────────────────────┘        │  /health/* /actuator/*   状态与指标     │
                              └──────────────────────────────────────┘
```

**部署方式（两种，推荐 A）**：

| 方式 | 说明 | 适用 |
|------|------|------|
| A. 同域部署（推荐） | Nginx 反代：`/console/*` → 前端静态产物；其余路径 → 网关。无需 CORS 配置 | 生产 |
| B. 跨域部署 | 前端独立域名/端口，网关需新增 CORS 白名单配置 `gateway.console.cors.allowed-origins` | 开发期 |

开发期默认使用 Vite dev server + 代理（`/admin`、`/api`、`/health`、`/actuator` 代理到 `http://localhost:8080`），避免 CORS。

### 3.2 前端技术选型（决策完成）

| 维度 | 选型 | 理由 |
|------|------|------|
| 框架 | **Vue 3 + TypeScript** | 团队技术栈一致、生态成熟、组合式 API 适合表单密集型管理台 |
| 构建 | **Vite 5+** | 启动快、产物轻、TS 开箱即用 |
| UI 组件库 | **Element Plus** | 表单/表格/弹窗/步骤条覆盖管理台全部交互 |
| 状态管理 | **Pinia** | 管理登录态、权限缓存、全局配置 |
| 路由 | **vue-router 4** | 路由级权限守卫 |
| HTTP | **axios** | 拦截器统一附加 Bearer Token、统一错误处理 |
| YAML 编辑 | **CodeMirror 6**（`@codemirror/lang-yaml`） | 轻量、行内校验标注；不引入 Monaco 避免体积膨胀 |
| YAML 解析 | **js-yaml**（`safeLoad`/`safeDump`） | Manifest 上传与在线编辑的 YAML↔JSON 转换 |
| 图表 | **ECharts 5** | 调用统计曲线、状态分布图 |
| 代码校验 | ESLint + Prettier | 统一风格 |

**明确不选**：不引入状态管理以外的重型框架；不做 SSR；不引入前端测试框架（本里程碑以手工验收 + API 层测试为主，见 §10）。

### 3.3 工程目录规划

```
ai-capability-gateway/
├── gateway-admin-console/            ← 新增：前端工程（独立 Node 工程，不参与 Maven）
│   ├── package.json                  （scripts: dev / build / preview）
│   ├── vite.config.ts                （dev 代理 /console base 配置）
│   ├── src/
│   │   ├── api/                      （axios 实例 + 各域 API 封装）
│   │   │   ├── http.ts               （拦截器：Bearer 注入、401 跳转、错误归一化）
│   │   │   ├── auth.ts               （登录/登出/whoami/provider 信息）
│   │   │   ├── capabilities.ts       （能力清单域）
│   │   │   ├── snapshots.ts          （快照域）
│   │   │   ├── monitor.ts            （监控域）
│   │   │   ├── acl.ts                （权限域）
│   │   │   └── system.ts             （系统配置域）
│   │   ├── router/                   （路由 + 权限守卫）
│   │   ├── stores/                   （Pinia：auth / capability / app）
│   │   ├── views/
│   │   │   ├── login/                （登录页）
│   │   │   ├── dashboard/            （总览看板）
│   │   │   ├── capabilities/         （能力清单：列表/详情/编辑）
│   │   │   ├── snapshots/            （快照列表/详情）
│   │   │   ├── monitor/              （监控：指标/审计/执行记录）
│   │   │   ├── acl/                  （权限：角色/权限词/ACL 映射）
│   │   │   └── system/               （系统配置：基础/限流/缓存）
│   │   ├── components/               （状态徽章、YamlEditor、操作确认弹窗等）
│   │   └── styles/                   （主题变量）
│   └── deploy/nginx-console.conf     （部署示例：同域反代）
│
├── gateway-adapter-web/              ← 扩展：新增 4 个 Controller + CORS 配置（§6）
├── gateway-domain/                   ← 扩展：新增 Port 与模型（§6.2）
├── gateway-adapter-postgresql/       ← 扩展：V4 迁移（ACL 表）+ 审计查询实现（§7）
└── gateway-bootstrap/                ← 扩展：Sentinel 规则管理服务、控制台管理员配置
```

### 3.4 网关侧扩展原则

1. 已有端点**零改动**（保持向后兼容）；新增端点使用新 Controller，不修改 `AdminController` 现有方法签名。
2. 新增查询类端点直接复用现有 Port（`ManifestRepository`、`CatalogPort`）；缺失能力通过新增 Domain Port 补齐（`AuditQueryPort`、`AclRepository`、`SnapshotListPort` 视需要）。
3. 所有新增管理端点统一执行 `authorizeAdmin` 门禁（§5.5 差距项修复）。
4. 响应 JSON 统一结构：`{ "status": <OK|ERROR>, "data": {...}, "error": {...} }`（错误时含稳定 `errorCode`）。

---

## 4. 认证集成设计（核心章节）

### 4.1 认证链路总览

管理后台必须完整支持"客户端发送认证令牌 → 网关验证 → 构造 Principal → 授权决策"的完整链路：

```
┌────────────────────────────────────────────────────────────────────┐
│ 管理后台前端 SPA                                                      │
│  登录页（双模式）                                                     │
│    ├─ stub 模式：提交用户名 → 网关签发占位 token                       │
│    └─ sa-token 模式：提交管理员凭证 → 网关验密并签发 HS256 JWT         │
│        │                                                            │
│        ▼                                                            │
│  Pinia auth store：token 持久化（localStorage，键 console_gateway_token）│
│        │                                                            │
│        ▼                                                            │
│  axios 请求拦截器：Authorization: Bearer <token>                     │
│        │                                                            │
│        ▼                                                            │
│  ┌─────────────────────────── 网关 ──────────────────────────────┐  │
│  │ RequestContextFactory.from(servletRequest) → RequestContext    │  │
│  │   ▼                                                           │  │
│  │ AuthenticationPort.authenticate(context)                      │  │
│  │   ├─ stub     → 任意非空 token 通过 → Principal(token, 0, [user], [*])│
│  │   └─ sa-token → SaJwtUtil 验签 → 提取 loginId/orgId/roles/perms│  │
│  │   ▼                                                           │  │
│  │ AuthorizationPort.authorizeAdmin(principal, action)（管理端点） │  │
│  │   ├─ stub     → 全放行                                        │  │
│  │   └─ sa-token → roles 含 admin 或 permissions 含 *            │  │
│  │   ▼                                                           │  │
│  │ Use Case → 响应                                               │  │
│  └───────────────────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────────────────┘
```

### 4.2 登录端点设计（网关侧新增）

管理后台不自行签发 Token（避免 JWT secret 落前端）。新增控制台专属端点，由网关在服务端签发：

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/admin/v1/console/auth/capabilities` | 返回认证模式信息（无需鉴权）：`{authProvider: "stub"\|"sa-token", consoleEnabled: true, loginMode: "token"\|"credentials"}` |
| POST | `/admin/v1/console/auth/login` | 登录，body 见下；返回签发 Token + Principal 摘要 |
| POST | `/admin/v1/console/auth/logout` | 登出（stub 无操作；sa-token 可选服务端失效，本里程碑仅前端清除） |
| GET | `/admin/v1/console/auth/whoami` | 返回当前 Token 对应的 Principal（前端刷新页面后恢复登录态用） |

**POST /admin/v1/console/auth/login 契约**：

```json
// stub 模式请求（loginMode=token）
{ "mode": "stub", "username": "admin" }

// sa-token 模式请求（loginMode=credentials）
{ "mode": "sa-token", "username": "console-admin", "password": "***" }
```

```json
// 响应 200
{
  "status": "OK",
  "data": {
    "token": "<signed-jwt-or-placeholder>",
    "expiresInSeconds": 7200,
    "principal": {
      "subject": "console-admin",
      "orgId": 0,
      "roles": ["admin"],
      "permissions": ["*"],
      "authMethod": "STUB-JWT | SA_TOKEN_JWT"
    }
  }
}

// 响应 401
{ "status": "ERROR", "error": { "errorCode": "AUTHENTICATION_FAILED", "message": "invalid credentials" } }
```

**服务端实现规则**：

1. stub 模式：不做任何校验，用 username 构造 Principal（roles=`[admin]`、permissions=`[*]`），token 返回固定占位串 `console-stub-<uuid>`（满足网关 stub 认证"任意非空 token 通过"）。
2. sa-token 模式：校验 `gateway.auth.console-admin.username/password`（application.yml 新增配置，见 §8.2）；通过后调用 `SaTokenAuthenticationAdapter.issueToken(subject, extraData)` 签发 JWT，extraData 携带 `orgId`、`roles=[admin]`、`permissions=[*]`。**JWT secret 仅在网关侧使用，绝不下发前端**。
3. 失败统一返回 401 `AUTHENTICATION_FAILED`，不区分"用户不存在/密码错误"（防枚举）。
4. 登录成功写入审计事件（event_type=`CONSOLE_LOGIN`，subject_digest、org_id、request_id 按现有约定）。
5. 管理后台专属端点实现放在新增 `ConsoleAuthController`（`gateway-adapter-web`），登录用例逻辑可内联在 Controller 或放 `gateway-application` 新增 `ConsoleAuthUseCase`（推荐后者，保持应用层纯 Java）。

### 4.3 Token 生命周期与前端管理

| 项 | 规则 |
|----|------|
| 存储 | `localStorage` 键 `console_gateway_token`；页面刷新后调用 `whoami` 校验有效性并恢复登录态 |
| 附加 | axios 请求拦截器统一附加 `Authorization: Bearer <token>`；`whoami`/`capabilities` 请求除外（或同样附加，网关兼容） |
| 失效处理 | 任意响应 401/403 → 清空 token → 跳转登录页（保留当前路由，登录后回跳） |
| 过期 | `expiresInSeconds` 倒计时；剩余 <5min 时提示重新登录（本里程碑不做自动刷新，Refresh Token 留待后续） |
| 登出 | 清空 localStorage + 调用 logout 端点 + 跳转登录页 |
| 安全提示 | 管理后台内置"令牌查看/复制"功能（sa-token 模式回显当前 JWT），用于对接外部客户端测试，界面标注敏感警告 |

### 4.4 授权模型：先授权后暴露

1. **页面级**：路由守卫根据 `whoami` 返回的 roles/permissions 判断菜单可见性（admin 角色或 `*` 权限可见全部；stub 模式恒可见）。
2. **操作级**：按钮级 `v-permission` 指令，按 `authorizeAdmin` 语义（admin 角色或 `*` 权限）控制。
3. **网关级（强制）**：所有 `/admin/v1/*` 新增端点与既有管理端点在 Use Case 入口调用 `AuthorizationPort.authorizeAdmin(principal, action)`，拒绝时返回 403 `PERMISSION_DENIED`（**既有 AdminController 差距项，本里程碑一并修复**，见 §5.5）。
4. **数据级**：能力详情页的协议配置（invocation 段）仅 admin 可见；非 admin 隐藏并显示"无权限查看协议配置"占位。

### 4.5 管理后台前端安全防护

- 登录表单提交走 POST JSON，禁用记住密码（浏览器自动填充关闭）。
- 管理后台部署要求 HTTPS（生产）。
- 同域部署时依赖网关现有安全头；跨域部署时 CORS 白名单严格配置。
- 前端不做任何签名/加解密逻辑（纯展示与编排），所有安全决策在网关侧完成。
- XSS 防护：所有后端返回内容经 Vue 默认转义渲染；YAML 编辑器内容仅文本展示。

---

## 5. 功能域设计

### 5.1 能力清单管理

#### 5.1.1 页面结构

**能力清单页（/capabilities）**：

| 区域 | 内容 |
|------|------|
| 顶部工具栏 | 搜索框（id/displayName/description/tags 模糊匹配）、状态筛选（全部/DRAFT/IMPORTED/VALIDATED/APPROVED/PUBLISHED/SUSPENDED/REJECTED/RETIRED）、分类筛选（tags、owner team、risk）、"导入 Manifest"按钮 |
| 主表格 | id、displayName、version、risk（徽章）、lifecycle（状态徽章）、tags、owner.team、updatedAt、操作列（详情/编辑/校验/审批/发布/停用/回滚） |
| 分页 | 服务端分页（默认 20 条/页） |

**能力详情页（/capabilities/:id/versions/:version）**：

| 区域 | 内容 |
|------|------|
| 头部 | id、version、状态徽章、digest、快捷操作（按状态动态显示） |
| Tab：概览 | displayName、description、risk、owner、tags、示例（positive/negative/synonyms） |
| Tab：Schema | inputSchema / publicSchema 树形展示（JSON 格式化 + 折叠） |
| Tab：协议配置 | invocation（admin 可见：protocol、interfaceName、method、serialization、arguments 绑定表）+ resilience + output 契约 |
| Tab：校验报告 | 10 步校验流水线结果（valid、errors、warnings），步骤级通过/失败展示 |
| Tab：审批记录 | capability_approval 记录（approver、role、decision、summary、时间） |
| Tab：审计 | 该能力相关的 audit_event 过滤列表 |

**Manifest 编辑器（导入/编辑弹窗或独立页）**：

- 支持两种输入：**YAML 文件上传**（`<input type=file>`，js-yaml 解析为 JSON 后预览）与 **在线编辑**（CodeMirror 6 YAML 模式）。
- 编辑流程：YAML 编辑 → 实时 js-yaml 解析（语法错误行内提示）→ "解析并预览"（JSON 树 + 与 `gateway-contract-schema` 的 JSON Schema 做前端预校验提示，最终以网关校验为准）→ 提交 `POST /admin/v1/manifests:import`（body 为转换后的 JSON `CapabilityManifest`）。
- 提交后展示校验结果：成功（IMPORTED + validationReport）→ 跳转详情页；失败（REJECTED + errors 列表）→ 回到编辑器并定位错误。
- 编辑已有 DRAFT/REJECTED 版本：拉取原始 Manifest → 编辑 → 以新版本号提交（**内容不可覆盖**，id+version 唯一）。

#### 5.1.2 状态流转与操作规则

界面展示状态机（与 `CapabilityLifecycle` 枚举一致，IMPORTED 为导入成功回执标记）：

```
DRAFT → IMPORTED → VALIDATED → APPROVED → PUBLISHED → SUSPENDED
                      ↑            │          │            │
                      │            │          ▼            │
                      │            └──→ REJECTED ←─────────┘
                      └──→（编辑后重新校验）                  （恢复需重新校验+新快照）
```

| 状态 | 可用操作（按钮按此渲染） |
|------|------------------------|
| DRAFT / IMPORTED | 编辑、提交校验 |
| VALIDATED | 审批（approve）、驳回（本里程碑以"编辑后重提"代替显式驳回） |
| APPROVED | 发布（publish） |
| PUBLISHED | 停用（suspend）、查看快照归属 |
| SUSPENDED | 查看（恢复需重新走 校验→审批→发布 流程） |
| REJECTED | 编辑后重新提交 |
| RETIRED | 仅查看 |

操作交互约定：审批需二次确认弹窗（显示确认摘要：capabilityId、version、risk）；发布需二次确认（显示将生成的快照说明）；停用必须填写 reason（必填）；回滚需从快照列表选择目标版本并二次确认（§5.2）。

#### 5.1.3 数据来源

- 列表/详情：新增 `GET /admin/v1/capabilities` 与 `GET /admin/v1/capabilities/{id}/versions/{version}`（基于 `ManifestRepository` + `capability_validation`/`capability_approval` 表）。
- 变更操作：复用现有 `POST /admin/v1/manifests:import`、`:validate`、`:approve`、`POST /admin/v1/releases:publish`、`/releases:rollback`、`/capabilities/{id}:suspend`。

### 5.2 快照管理

#### 5.2.1 页面结构

**快照列表页（/snapshots）**：

| 区域 | 内容 |
|------|------|
| 当前快照卡片 | 当前 ACTIVE 快照：版本号、environment、digest、capabilityCount、publishedAt、publishedBy、"查看详情"、"回滚到此"（禁用于当前版本） |
| 历史快照表 | snapshot_version（降序）、environment、status（ACTIVE/SUPERSEDED）、digest（截断展示）、capabilityCount、publishedAt、publishedBy、操作列（查看详情/回滚） |
| 发布操作区 | environment 下拉（默认 production）、"发布新快照"按钮（二次确认后调用 `releases:publish`） |

**快照详情页（/snapshots/:version）**：

| 区域 | 内容 |
|------|------|
| 头部 | snapshotVersion、environment、status、digest、publishedAt、publishedBy |
| 能力列表 | 快照包含的能力（id + version + manifestDigest + policyRef），点击跳转能力详情 |
| 操作 | "回滚到此版本"（二次确认，说明将生成新版本而非修改历史） |

#### 5.2.2 交互规则

- 回滚语义：`POST /admin/v1/releases:rollback` 将历史快照内容**复制为新版本**，不修改历史；前端提示文案必须说明这一点。
- 发布语义：将当前所有 APPROVED 能力固化为新快照；发布后快照列表自动刷新并高亮新版本。
- 快照间能力差异：详情页可选提供"与上一版本对比"（新增/移除/变更的 capability 列表），数据由前端对两次 `GET /admin/v1/releases/{version}` 结果做本地 diff（本里程碑不做服务端 diff）。

#### 5.2.3 数据来源

- 列表：新增 `GET /admin/v1/releases`（`CatalogPort` 扩展 `listSnapshots(environment)` 或复用 JDBC 查询）。
- 详情：复用现有 `GET /admin/v1/releases/{snapshotVersion}`。
- 变更：复用现有 `POST /admin/v1/releases:publish`、`POST /admin/v1/releases:rollback`。

### 5.3 运行监控

#### 5.3.1 总览看板（/dashboard）

| 卡片 | 数据来源 |
|------|---------|
| 网关健康 | `GET /health/readiness`（database / activeSnapshot / requiredSecrets / adapterInitialization 逐项 UP/DOWN） |
| 当前快照 | `GET /admin/v1/releases`（当前版本 + 能力数） |
| 今日调用量 / 成功率 / 平均耗时 | 新增 `GET /admin/v1/stats/summary`（§6.2.5） |
| 认证模式 | `GET /admin/v1/console/auth/capabilities` |

#### 5.3.2 监控页（/monitor）

| 区块 | 内容 | 数据来源 |
|------|------|---------|
| 调用趋势 | 近 24h 调用量折线（按小时聚合）、成功率、P50/P95 耗时 | `GET /admin/v1/stats/timeseries?window=24h&granularity=hour` |
| 能力排行 | Top N 能力调用量/失败量条形图 | `GET /admin/v1/stats/capabilities?topN=10` |
| 实时指标 | 网关 JVM/HTTP 指标（可选）：直接透传 `/actuator/metrics/http.server.requests` 关键项 | Actuator |
| 审计查询 | 事件类型筛选（REQUEST_ACCEPTED/STARTED/TERMINAL/CONSOLE_LOGIN/管理操作）、时间范围、capabilityId、result_code 过滤，分页表格 | `GET /admin/v1/audits`（本里程碑实现） |
| 执行记录 | execution_record 列表：execution_id、capability、snapshot、status、时间 | 新增 `GET /admin/v1/executions`（可选，若审计查询已覆盖可合并） |
| 错误日志 | 按 result_code 非成功过滤的审计记录，展示 error 明细 | 审计查询 + `details` 字段 |

#### 5.3.3 数据来源与实现

- 统计端点基于 `execution_record` / `audit_event` 表 SQL 聚合（JDBC 原生查询，新增 `StatsQueryPort` + `JdbcStatsQueryAdapter`）。
- 审计查询实现：新增 Domain Port `AuditQueryPort`（`query(AuditQueryCriteria)`），`gateway-adapter-postgresql` 提供 `JdbcAuditQueryAdapter`；`GET /admin/v1/audits` 从骨架升级为真实实现（保持路径与参数兼容：eventType / capabilityId / limit，新增分页与时间范围参数）。
- 所有监控数据延迟容忍：统计端点允许秒级聚合缓存（Caffeine 30s，复用 gateway-adapter-redis 的 caffeine 依赖或 bootstrap 自建）。

### 5.4 权限管理

#### 5.4.1 页面结构

**角色管理（/acl/roles）**：

| 区域 | 内容 |
|------|------|
| 角色表 | role（名称）、描述、关联权限数、关联用户数（占位）、创建时间、操作（编辑/删除） |
| 创建/编辑弹窗 | role 名称 + 权限词多选（`domain:resource:action` 三段式，禁用通配符） |

**权限词管理（/acl/permissions）**：

| 区域 | 内容 |
|------|------|
| 权限词表 | permission（`domain:resource:action`）、描述、被引用角色数、操作（删除前校验引用） |
| 创建弹窗 | permission 名称（正则校验 `^[a-z][a-z0-9]*(:[a-z][a-z0-9]*){2}$`）+ 描述 |

**能力访问控制（/acl/capabilities）**：

| 区域 | 内容 |
|------|------|
| 能力 ACL 表 | capabilityId、当前授权角色（多选展示）、required permissions（来自 Manifest spec.authorization.permissions）、操作（编辑授权角色） |
| 编辑弹窗 | 能力 → 角色多选映射，保存后写入 ACL 表并通知网关刷新 |

**授权策略总览（/acl）**：

- 展示"角色 → 权限 → 能力"的关联视图（三层 drill-down），说明当前生效策略。
- 展示 `allowAllIfAclEmpty` 当前取值与提示（ACL 为空时默认放行的降级规则，见 §2.3/§5.5）。

#### 5.4.2 实现方案（网关侧）

当前 `SaTokenAuthorizationAdapter` 的 ACL 为**内存实现**（`loadAcl()` 空实现，`grant()` 可编程注册）。本里程碑：

1. **新增数据库表**（`V4` 迁移，§7）：`role`、`permission`、`role_permission`、`capability_acl`（capabilityId → 允许角色集合，含 policy_version）。
2. **新增 Domain Port `AclRepository`**：`listRoles/listPermissions/upsertCapabilityAcl/...`；`gateway-adapter-postgresql` 提供 `JdbcAclRepository`。
3. **改造 `SaTokenAuthorizationAdapter`**：新增构造重载注入 `AclRepository`（保持无参构造可用，兼容既有装配）；`loadAcl()` 改为从数据库加载并缓存（`ConcurrentHashMap`，支持运行时刷新）；新增 `refreshAcl()` 供 ACL 变更后调用。
4. **新增管理端点**：`/admin/v1/acl/capabilities`（GET 列表 / PUT 更新）、`/admin/v1/roles`、`/admin/v1/permissions`（CRUD，§6.2.6）。
5. **差距项修复**（贯穿全章节）：`AdminController` 现有 6 个变更端点与新增管理端点统一在 Use Case 入口执行 `authorizeAdmin`；stub 模式下由 `StubAuthorizationPort` 保持"全放行"语义不变，sa-token 模式下严格执行 admin 角色 / `*` 权限。

> **范围边界**：用户实体的 CRUD 不在本里程碑（网关无用户表，Principal 由外部 IdP/Token 声明）；角色/权限词/能力 ACL 为网关自治管理，满足"能力级访问控制"需求。

#### 5.4.3 数据来源

- 全部来自新增 `/admin/v1/acl/*`、`/admin/v1/roles`、`/admin/v1/permissions` 端点（§6.2.6）。
- Manifest 声明权限（`spec.authorization.permissions`）来自能力详情接口。

### 5.5 系统配置

#### 5.5.1 基础配置查看（/system/config）

| 区块 | 内容 | 数据来源 |
|------|------|---------|
| 运行环境 | environment、auth.provider、cache.provider、ratelimit.provider、snapshot.max-lag-millis | 新增 `GET /admin/v1/config`（仅非敏感项） |
| 弹性参数 | default-timeout-ms、rate-limits（per-user/per-tenant/per-application/llm-concurrent/provider-concurrent） | 同上 |
| 审计参数 | batch-size、batch-wait-millis、queue-capacity、retention | 同上 |
| 变更说明 | 展示"配置文件修改需重启生效；运行时可变项仅限限流规则"提示 | — |

**脱敏规则**：`jwt-secret-key`、`api-key`、数据库密码、Redis 密码等敏感项一律不返回（字段直接省略）；地址类配置仅返回 host:port。

#### 5.5.2 限流规则管理（/system/ratelimit）

| 区域 | 内容 | 数据来源 |
|------|------|---------|
| 流控规则表 | 资源名（gateway:global / gateway:llm:routing / gateway:capability:{id}）、维度（QPS/并发）、阈值、控制行为（快速失败/排队）、排队时间 | `GET /admin/v1/ratelimit/rules`（读取 `FlowRuleManager` / `DegradeRuleManager` 当前规则） |
| 降级规则表 | 资源名、策略（异常比例/慢调用比例）、阈值、时间窗 | 同上 |
| 新增/编辑弹窗 | 资源名（提供已有能力下拉 + 自定义输入）、规则类型（flow/degrade）、阈值、策略参数 | `POST /admin/v1/ratelimit/rules` |
| 删除 | 删除指定资源规则 | `DELETE /admin/v1/ratelimit/rules/{resource}?type=flow\|degrade` |

**实现要点**：
- `gateway-bootstrap/ratelimit` 新增 `SentinelRuleAdminService`：封装 `FlowRuleManager.loadRules()` / `DegradeRuleManager.loadRules()` 的读改写（读取当前规则 → 增/删/改 → 全量 reload）。
- 规则为**运行时内存态**，重启后回落到 `SentinelRuleInitializer` 硬编码基线；界面显著标注此限制（"规则保存在运行时内存，重启后恢复基线配置"），Nacos DataSource 演进留待后续（符合技术选型决策 #3）。
- 仅 `ratelimit.provider=sentinel` 时端点可用；stub 模式返回 409 `RATELIMIT_DISABLED`。

#### 5.5.3 缓存配置查看（/system/cache）

| 区域 | 内容 | 数据来源 |
|------|------|---------|
| 缓存模式 | cache.provider（stub/redis）、redis 地址（脱敏）、database | `GET /admin/v1/config` |
| 快照缓存 | L1 TTL（Caffeine，默认 30s）、当前加载快照版本、最近刷新时间 | 新增 `GET /admin/v1/cache/status` |
| 说明 | redis 模式下展示"Write-Through + Pub/Sub 热加载"架构说明 | 静态文案 |

本里程碑缓存**仅查看**，不做运行时修改。

#### 5.5.4 网关运行状态页（/system/status，可选并入 dashboard）

- 展示 `GET /actuator/health` 详情、`/health/readiness` 逐项状态。
- 展示当前进程信息（uptime 等，来自 `/actuator/metrics` 或 `info`）。

---

## 6. API 兼容层设计

### 6.1 复用端点（零改动）

| 方法 | 路径 | 管理后台用途 |
|------|------|-------------|
| POST | `/admin/v1/manifests:import` | Manifest 导入（body：CapabilityManifest JSON） |
| POST | `/admin/v1/capabilities/{id}/versions/{version}:validate` | 重新校验 |
| POST | `/admin/v1/capabilities/{id}/versions/{version}:approve` | 审批（body：`{approver}`，approver 取 whoami.subject） |
| POST | `/admin/v1/releases:publish` | 发布快照（body：`{environment}`） |
| POST | `/admin/v1/releases:rollback` | 回滚（body：`{targetSnapshotVersion, environment}`） |
| POST | `/admin/v1/capabilities/{id}:suspend` | 停用（body：`{reason, operator}`，operator 取 whoami.subject） |
| GET | `/admin/v1/releases/{snapshotVersion}` | 快照详情 |
| GET | `/health/readiness` `/health/liveness` | 运行状态 |
| GET | `/actuator/health` `/actuator/metrics` `/actuator/prometheus` | 指标（透传展示） |
| POST | `/api/v1/natural-language/queries` | 调试面板（可选，管理员自测入口） |

### 6.2 新增端点契约

所有新增端点要求 `Authorization: Bearer <token>`，统一响应结构；管理操作统一 403 语义。新增端点集中在新 Controller（见 6.2.8）。

#### 6.2.1 控制台认证

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/admin/v1/console/auth/capabilities` | 认证模式探测（匿名可访问） |
| POST | `/admin/v1/console/auth/login` | 登录签发 Token（匿名可访问，见 §4.2 契约） |
| POST | `/admin/v1/console/auth/logout` | 登出 |
| GET | `/admin/v1/console/auth/whoami` | 当前 Principal |

#### 6.2.2 能力查询

**GET /admin/v1/capabilities** — 能力列表（分页 + 过滤 + 搜索）

```
Query 参数：
  lifecycle   （可选）CapabilityLifecycle 枚举值
  risk        （可选）READ_ONLY | WRITE_LOW | WRITE_HIGH
  tag         （可选）标签精确匹配
  team        （可选）owner.team 精确匹配
  q           （可选）id/displayName/description 模糊匹配（ILIKE）
  page        （默认 1）从 1 开始
  size        （默认 20，最大 100）
```

```json
// 200
{
  "status": "OK",
  "data": {
    "total": 42,
    "page": 1,
    "size": 20,
    "items": [
      {
        "capabilityId": "order.detail.query",
        "version": "1.0.0",
        "displayName": "查询订单详情",
        "description": "根据订单号查询当前组织下的单个订单详情。",
        "risk": "READ_ONLY",
        "lifecycle": "PUBLISHED",
        "tags": ["order", "query", "read-only"],
        "ownerTeam": "order-platform",
        "sha256Digest": "ab12...",
        "updatedAt": "2026-08-01T10:00:00Z",
        "snapshotVersions": [1, 2, 3]
      }
    ]
  }
}
```

实现：新增 `CapabilityQueryUseCase`（gateway-application），复用 `ManifestRepository`（`findAll()`/`findByIdAndVersion`）联合 `capability_validation`、`capability_approval`、`catalog_snapshot_item` 表组装；列表不含 invocation 等敏感段。

**GET /admin/v1/capabilities/{id}/versions/{version}** — 能力详情

```json
// 200（节选）
{
  "status": "OK",
  "data": {
    "manifest": { "apiVersion": "gateway.ai/v1", "kind": "Capability", "metadata": {...}, "spec": {...} },
    "validation": { "valid": true, "errors": [], "warnings": [], "validatedAt": "..." },
    "approval": { "approver": "admin", "role": "admin", "decision": "APPROVED", "summary": {...}, "approvedAt": "..." },
    "lifecycle": "PUBLISHED",
    "publishedSnapshotVersions": [1, 2, 3]
  }
}
```

规则：`spec.invocation` 仅当调用者通过 `authorizeAdmin` 时返回完整内容，否则置为 `null` 并返回 `invocationHidden: true`。

#### 6.2.3 快照查询

**GET /admin/v1/releases** — 快照列表

```
Query 参数：environment（默认 production）、page、size（默认 20）
```

```json
// 200
{
  "status": "OK",
  "data": {
    "current": { "snapshotVersion": 3, "environment": "production", "status": "ACTIVE",
                 "digest": "abc...", "capabilityCount": 5,
                 "publishedAt": "...", "publishedBy": "admin" },
    "items": [
      { "snapshotVersion": 3, "environment": "production", "status": "ACTIVE", "digest": "abc...", "capabilityCount": 5, "publishedAt": "...", "publishedBy": "admin" },
      { "snapshotVersion": 2, "environment": "production", "status": "SUPERSEDED", ... },
      { "snapshotVersion": 1, "environment": "production", "status": "SUPERSEDED", ... }
    ],
    "total": 3
  }
}
```

实现：`CatalogPort` 新增 `listSnapshots(environment)` 方法（domain port 扩展），`JdbcCatalogPort` 实现（按 snapshot_version 降序，status 判定：最新一条为 ACTIVE，其余为 SUPERSEDED）。

#### 6.2.4 审计查询（升级骨架）

**GET /admin/v1/audits** — 审计事件查询

```
Query 参数：
  eventType     （可选）REQUEST_ACCEPTED | STARTED | TERMINAL | CONSOLE_LOGIN | 管理事件类型
  capabilityId  （可选）
  requestId     （可选）
  resultCode    （可选）非空即过滤（如 SUCCEEDED / FAILED / 错误码）
  from / to     （可选）ISO 时间范围
  page / size   （默认 1 / 20，最大 100）
```

```json
// 200
{
  "status": "OK",
  "data": {
    "total": 128,
    "page": 1,
    "size": 20,
    "items": [
      {
        "eventId": 1024,
        "eventType": "TERMINAL",
        "timestamp": "2026-08-10T03:12:44Z",
        "subjectDigest": "sha256:...",
        "orgId": 10001,
        "requestId": "req-001",
        "operationId": null,
        "capabilityId": "order.detail.query",
        "capabilityVersion": "1.0.0",
        "snapshotVersion": 3,
        "resultCode": "SUCCEEDED",
        "durationMs": 142,
        "details": {}
      }
    ]
  }
}
```

实现：新增 Domain Port `AuditQueryPort`（`List<AuditEvent> query(AuditQueryCriteria)`），`JdbcAuditQueryAdapter` 实现；`AdminController.queryAudits` 骨架替换为真实实现（保留原 eventType/capabilityId/limit 参数兼容，新增上述参数）。**注意**：`subjectDigest` 为摘要值，界面展示哈希前缀即可，不回显明文主体（隐私）。

#### 6.2.5 统计查询

**GET /admin/v1/stats/summary** — 汇总

```json
// 200
{
  "status": "OK",
  "data": {
    "windowHours": 24,
    "totalExecutions": 15230,
    "successCount": 15001,
    "successRate": 0.985,
    "avgDurationMs": 138,
    "p95DurationMs": 420,
    "activeCapabilities": 5,
    "clarificationCount": 96
  }
}
```

**GET /admin/v1/stats/timeseries** — 时序（`?window=24h&granularity=hour`）

```json
{
  "status": "OK",
  "data": {
    "granularity": "hour",
    "points": [
      { "bucket": "2026-08-09T04:00:00Z", "total": 620, "success": 610, "failed": 10, "avgDurationMs": 131 }
    ]
  }
}
```

**GET /admin/v1/stats/capabilities** — 能力维度（`?topN=10`）

```json
{
  "status": "OK",
  "data": {
    "items": [
      { "capabilityId": "order.detail.query", "total": 8210, "success": 8100, "failed": 110, "avgDurationMs": 120 }
    ]
  }
}
```

实现：新增 Domain Port `StatsQueryPort` + `JdbcStatsQueryAdapter`（基于 `execution_record` 聚合，`created_at` 分桶）；结果 Caffeine 30s 缓存。

**GET /admin/v1/executions** — 执行记录（可选）

```
Query 参数：capabilityId、status、from、to、page、size
```

#### 6.2.6 权限管理

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/admin/v1/roles` | 角色列表（含权限词） |
| POST | `/admin/v1/roles` | 创建角色（body：`{name, description, permissions[]}`） |
| PUT | `/admin/v1/roles/{name}` | 更新角色 |
| DELETE | `/admin/v1/roles/{name}` | 删除角色（被 ACL 引用时 409） |
| GET | `/admin/v1/permissions` | 权限词列表 |
| POST | `/admin/v1/permissions` | 创建权限词（名称正则 `^[a-z][a-z0-9]*(:[a-z][a-z0-9]*){2}$`，禁止 `*`） |
| DELETE | `/admin/v1/permissions/{name}` | 删除权限词（被角色引用时 409） |
| GET | `/admin/v1/acl/capabilities` | 能力 ACL 列表（capabilityId、allowedRoles、requiredPermissions） |
| PUT | `/admin/v1/acl/capabilities/{id}` | 更新能力授权角色（body：`{roles[]}`），成功后触发 `SaTokenAuthorizationAdapter.refreshAcl()` |
| GET | `/admin/v1/acl/policy` | 授权策略总览（`allowAllIfAclEmpty` 取值、ACL 条数、生效版本） |

```json
// GET /admin/v1/acl/capabilities → 200
{
  "status": "OK",
  "data": {
    "allowAllIfAclEmpty": true,
    "items": [
      { "capabilityId": "order.detail.query", "allowedRoles": ["order-analyst", "admin"], "requiredPermissions": ["order:detail:read"] }
    ]
  }
}
```

#### 6.2.7 系统配置

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/admin/v1/config` | 网关非敏感配置（§5.5.1 清单） |
| GET | `/admin/v1/ratelimit/rules` | Sentinel 当前规则（flow + degrade 两组） |
| POST | `/admin/v1/ratelimit/rules` | 新增/更新规则（body 见下） |
| DELETE | `/admin/v1/ratelimit/rules/{resource}` | 删除规则（`?type=flow|degrade`） |
| GET | `/admin/v1/cache/status` | 缓存模式与快照缓存状态 |

```json
// POST /admin/v1/ratelimit/rules body（flow 示例）
{
  "type": "flow",
  "resource": "gateway:capability:order.detail.query",
  "grade": "QPS",                    // QPS | CONCURRENCY
  "count": 100,
  "controlBehavior": "REJECT",       // REJECT 快速失败 | QUEUE 排队
  "maxQueueingMs": 500
}
// degrade 示例
{
  "type": "degrade",
  "resource": "gateway:capability:order.detail.query",
  "strategy": "ERROR_RATIO",         // ERROR_RATIO | SLOW_RATIO
  "ratio": 0.5,
  "slowThresholdMs": 3000,           // SLOW_RATIO 时必填
  "timeWindowSeconds": 30,
  "statIntervalMs": 10000,
  "minRequestAmount": 5
}
```

规则：`resource` 前缀必须为 `gateway:`（白名单校验，防误配）；`gateway:global` 与 `gateway:llm:routing` 允许修改阈值但不允许删除；仅 `ratelimit.provider=sentinel` 时可用（stub 返回 409 `RATELIMIT_DISABLED`）。

#### 6.2.8 新增 Controller 与用例清单

| 新增文件（gateway-adapter-web） | 端点域 |
|--------------------------------|--------|
| `ConsoleAuthController` | `/admin/v1/console/auth/*` |
| `CatalogQueryController` | `/admin/v1/capabilities`、`/admin/v1/releases`（GET 列表）、`/admin/v1/executions` |
| `MonitorQueryController` | `/admin/v1/audits`（升级）、`/admin/v1/stats/*` |
| `AclAdminController` | `/admin/v1/roles`、`/admin/v1/permissions`、`/admin/v1/acl/*` |
| `SystemConfigController` | `/admin/v1/config`、`/admin/v1/ratelimit/rules`、`/admin/v1/cache/status` |

| 新增文件（gateway-application） | 说明 |
|--------------------------------|------|
| `ConsoleAuthUseCase` | 登录签发/登出/whoami |
| `CapabilityQueryUseCase` | 能力列表/详情组装 |
| `AuditQueryUseCase` | 审计查询（含分页） |
| `StatsQueryUseCase` | 统计聚合 |
| `AclManageUseCase` | 角色/权限/ACL 管理（写操作事务化 + 刷新授权缓存） |
| `ConfigQueryUseCase` | 非敏感配置读取 |

| 新增文件（gateway-domain） | 说明 |
|----------------------------|------|
| `AuditQueryPort`、`StatsQueryPort`、`AclRepository`（Port）、`AclEntry`/`Role`/`Permission`（模型） | 查询与权限模型 |
| `CatalogPort.listSnapshots(environment)` | Port 方法扩展 |

| 新增文件（gateway-adapter-postgresql） | 说明 |
|----------------------------------------|------|
| `JdbcAuditQueryAdapter`、`JdbcStatsQueryAdapter`、`JdbcAclRepository` | 查询/权限落地实现 |
| `db/migration/V4__acl_and_console.sql` | §7 迁移 |

| 新增文件（gateway-bootstrap） | 说明 |
|------------------------------|------|
| `ratelimit/SentinelRuleAdminService` | Sentinel 规则读改写 |
| `config/ConsoleCorsConfiguration` | CORS 白名单（仅配置了 allowed-origins 时生效） |

### 6.3 错误码约定

| HTTP | errorCode | 场景 |
|------|-----------|------|
| 401 | `AUTHENTICATION_FAILED` | 未携带/无效 Token（前端触发登录跳转） |
| 403 | `PERMISSION_DENIED` | 已认证但 `authorizeAdmin` 拒绝 |
| 400 | `VALIDATION_FAILED` | 请求参数/Manifest 校验失败（含校验报告） |
| 404 | `NOT_FOUND` | 资源不存在 |
| 409 | `CONFLICT` | 状态冲突（如重复审批、删除被引用角色、stub 模式下操作限流） |
| 409 | `RATELIMIT_DISABLED` | ratelimit.provider 非 sentinel 时操作限流规则 |
| 429 | `RATE_LIMITED` | 管理后台自身请求被网关限流（展示重试提示） |
| 500 | `INTERNAL_ERROR` | 未知错误（响应不携带堆栈） |

统一错误响应体：`{ "status": "ERROR", "error": { "errorCode": "...", "message": "...", "details": {...} } }`。

---

## 7. 数据模型扩展（V4 迁移）

新增 `V4__acl_and_console.sql`（`gateway-adapter-postgresql`），与现有 V1~V3 风格一致：

```sql
-- V4__acl_and_console.sql
-- Console/ACL support: roles, permission words, role-permission mapping,
-- capability ACL, and console settings.

CREATE TABLE role (
    name VARCHAR(64) PRIMARY KEY,
    description VARCHAR(256) NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE permission (
    name VARCHAR(128) PRIMARY KEY,      -- domain:resource:action 三段式
    description VARCHAR(256) NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE role_permission (
    role_name VARCHAR(64) NOT NULL REFERENCES role(name),
    permission_name VARCHAR(128) NOT NULL REFERENCES permission(name),
    policy_version BIGINT NOT NULL DEFAULT 1,
    PRIMARY KEY (role_name, permission_name)
);

CREATE TABLE capability_acl (
    capability_id VARCHAR(256) NOT NULL,
    capability_version VARCHAR(32) NOT NULL,
    allowed_role VARCHAR(64) NOT NULL REFERENCES role(name),
    policy_version BIGINT NOT NULL DEFAULT 1,
    updated_by VARCHAR(64) NOT NULL DEFAULT 'SYSTEM',
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (capability_id, capability_version, allowed_role)
);

CREATE TABLE console_setting (
    setting_key VARCHAR(64) PRIMARY KEY,
    setting_value VARCHAR(512) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

要点：
- `capability_acl` 以 (capabilityId, capabilityVersion) 为能力维度（与 `capability_manifest` 主键对齐）；`SaTokenAuthorizationAdapter` 运行时按 capabilityId 聚合（当前版本维度）。
- `policy_version` 供后续策略快照/回滚演进预留（与授权映射关系模型设计对齐）。
- `console_setting` 预留（本里程碑可空表，用于将来存储非敏感控制台偏好）。

---

## 8. 非功能需求

### 8.1 性能

- 前端首屏加载 < 3s（gzip 后 JS 产物 < 500KB 预算，CodeMirror 按路由懒加载）。
- 列表/查询端点 P95 < 300ms（PostgreSQL 索引覆盖：`capability_manifest(lifecycle)`、`audit_event(timestamp)`、`execution_record(created_at)` 已有或新增）。
- 统计端点 30s 缓存；审计/列表分页深度 ≤ 10000（超出提示缩小范围）。

### 8.2 安全

- 管理端点全部走 `authorizeAdmin`（含既有 6 个变更端点的差距修复）。
- 敏感配置不返回；审计 `subjectDigest` 不回显明文。
- 前端不存储/不接触任何密钥；Token 仅 localStorage（界面提示风险，生产建议后续迁移 httpOnly Cookie 方案，记为开放问题）。
- 登录失败不做枚举区分；管理后台访问建议限制内网/白名单（部署层）。

### 8.3 可观测性

- 管理后台自身请求沿用网关 `TraceContextFilter`（traceId 透传，前端在错误弹窗展示 traceId 便于排查）。
- 网关新增端点纳入现有审计：管理操作（登录/ACL 变更/规则变更）写 `audit_event`。

### 8.4 兼容性

- 前端最低支持 Chromium 100+ / Edge 100+ / Firefox 100+（管理台内部使用，不做 IE）。
- 网关侧新增端点不修改任何现有端点行为；`AdminController` 仅做审计端点内部实现替换（路径/参数兼容）。

---

## 9. 里程碑与交付物

| 阶段 | 内容 | 交付物 | 预估 |
|------|------|--------|------|
| M0 | 环境准备 | 前端脚手架（Vite+Vue3+TS+Element Plus+Pinia+Router）、网关开发环境 | 0.5d |
| M1 | 认证集成 + 布局 | ConsoleAuthController、登录页、路由守卫、axios 拦截器、主布局（侧边菜单/顶栏）、whoami 恢复登录态 | 2d |
| M2 | 能力清单管理 | CatalogQueryController 查询端点、能力列表/详情/编辑器（YAML 上传+在线编辑）、生命周期操作闭环 | 3d |
| M3 | 快照管理 | `CatalogPort.listSnapshots`、快照列表/详情/发布/回滚 | 1.5d |
| M4 | 运行监控 | AuditQueryPort/StatsQueryPort + 适配器、审计查询页、统计看板（ECharts）、健康状态卡片 | 2.5d |
| M5 | 权限管理 | V4 迁移、AclRepository、SaTokenAuthorizationAdapter 改造、ACL/角色/权限页、authorizeAdmin 差距修复 | 2d |
| M6 | 系统配置 | ConfigQueryUseCase、SentinelRuleAdminService、配置/限流/缓存页 | 1.5d |
| M7 | 联调验收 | stub/sa-token 双模式 E2E、文档完善、Nginx 部署示例验证 | 1.5d |

合计约 **14.5 人日**。

---

## 10. 验收标准

### 10.1 功能验收（E2E 脚本化场景）

1. **认证链路（双模式）**：
   - stub 模式：登录页输入任意用户名 → 获取 token → 访问各页面成功 → 退出登录 → 重新登录。
   - sa-token 模式：配置 `GATEWAY_AUTH_PROVIDER=sa-token` + `GATEWAY_AUTH_JWT_SECRET` + console-admin 凭证 → 登录 → 网关日志确认 JWT 验签成功 → 将控制台 token 复制到 `GatewayApiClient` 可成功调用管理 API（验证令牌与网关验证链路闭环）。
   - 无效 token：修改 localStorage token → 任意请求返回 401 → 自动跳转登录页。
2. **能力生命周期闭环**：上传 order-detail-query.yaml → 解析预览 → 导入（IMPORTED + 校验报告）→ 重新校验（VALIDATED）→ 审批（APPROVED）→ 发布（快照 +1）→ 能力详情显示 PUBLISHED → 停用（填 reason）→ SUSPENDED → 恢复走重新校验/审批/发布 → 回滚到历史快照。
3. **快照管理**：发布 3 个快照 → 列表显示当前 ACTIVE + 2 个 SUPERSEDED → 详情能力列表正确 → 回滚到 v1 → 新快照版本生成且内容与 v1 一致。
4. **监控**：发起若干自然语言查询（含失败场景，如 ERROR/TIMEOUT 订单号）→ 统计看板数据更新（30s 内）→ 审计查询按 eventType/resultCode/时间过滤正确。
5. **权限管理**：创建角色 `order-analyst` → 授权 `order.detail.query` → 用带该角色的 JWT（SaTokenIssuer 签发）查询 → 可见该能力；未授权能力不可见（先授权后暴露）。ACL 为空时默认放行提示正确展示。
6. **限流规则**：sentinel 模式下新增能力级 QPS=1 规则 → 连续调用触发 429 → 删除规则恢复；stub 模式下操作规则返回 `RATELIMIT_DISABLED`。
7. **配置查看**：非敏感配置正确展示；敏感字段（secret/api-key）确认不出现。

### 10.2 非功能验收

- 构建：`npm run build` 零错误；网关 `mvn -f ai-capability-gateway/pom.xml clean verify` 通过（含 ArchUnit）。
- 安全：管理端点未带 token → 401；非 admin 角色（sa-token 模式）访问管理端点 → 403。
- 兼容：既有 `AdminController` 端点 curl 回归通过（行为不变）。

---

## 11. 风险与开放问题

| # | 风险/问题 | 影响 | 应对/决策 |
|---|-----------|------|----------|
| 1 | 既有 `AdminController` 变更端点未执行 `authorizeAdmin` | 管理操作无鉴权（stub 模式当前可接受，sa-token 模式必须修复） | M5 强制修复：Use Case 入口统一门禁；修复前管理后台仅允许 stub 模式联调管理操作 |
| 2 | Sentinel 规则为运行时内存态 | 重启回落到硬编码基线，运行时修改丢失 | 界面显著标注；Nacos DataSource 演进（技术选型决策 #3 预留） |
| 3 | ACL 数据库实现与 `SaTokenAuthorizationAdapter` 内存实现的改造耦合 | 影响现有 sa-token 装配 | 保持无参构造兼容，新增注入重载；`refreshAcl()` 幂等 |
| 4 | Token 存 localStorage 的 XSS 风险 | 令牌被盗 | 界面提示；生产 HTTPS；httpOnly Cookie 方案列为后续 |
| 5 | 审计 `subjectDigest` 为哈希 | 界面无法展示用户名 | 本里程碑展示摘要前缀；如需明文需新增主体映射表（后续） |
| 6 | 统计端点基于 `execution_record` | 数据量增长后聚合变慢 | 30s 缓存 + 分页限制；后续可加物化视图/时序库 |
| 7 | 前端工程与 Maven 构建分离 | CI 需要两条构建链 | 明确产物交付：`gateway-admin-console/dist` 静态部署，Nginx 示例已提供 |
| 8 | YAML 导入为前端转换（js-yaml → JSON）后调 JSON 端点 | 与网关 JSON 契约一致但多一层转换 | 转换在提交前有"解析预览"步骤，错误可定位；网关侧 YAML 变体端点列为后续增强 |

---

## 12. 参考文档

- `docs/extensibility-tech-selection.md` — 可插拔认证/缓存/限流选型与决策
- `docs/workflow-and-integration-guide.md` — 控制面/运行面工作流与 API 参考
- `gateway-example/README.md` — 示例与认证集成场景（stub / sa-token / custom）
- `README.md` — 网关架构与核心设计原则
