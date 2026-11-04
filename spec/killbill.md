# Feature Specification: Kill Bill

> 开源计费与支付平台。本仓库为其 fork：保留官方业务核心，将插件运行时由 Apache Felix/OSGi 替换为自研轻量级插件运行时（LPR），并把官方 6 个仓库合并为单 reactor。

## 范围（当前版本）

**覆盖（Kill Bill 核心域）**：
- 账户（Account）与租户（Tenant）管理。
- 商品目录（Catalog）、定价计划（Plan）、价格表（PriceList）与货币（Currency）。
- 订阅（Subscription）与权益（Entitlement）生命周期。
- 用量（Usage）采集与汇总。
- 发票（Invoice）生成、项调整与支付关联。
- 支付（Payment）路由、插件网关调用、支付控制。
- 逾期（Overdue）与催缴（Dunning）策略。
- 通知队列（Notification Queue）与持久化事件总线（Persistent Bus）。
- 安全（Security）、认证授权（Shiro/RBAC）与多租户隔离。
- JAX-RS REST API 与插件 HTTP Servlet 路由。
- 插件运行时（LPR）：发现、加载、隔离、生命周期、服务注册、事件订阅、版本热更新。
- 16 个官方插件的迁移与在 reactor 内维护。

**不覆盖**：
- OSGi 完整规范、Package Wiring、Bundle Fragment。
- JVM 级安全沙箱或恶意代码防护。
- 跨进程/分布式插件部署。
- 插件市场、付费分发、自动依赖解析。

## User Scenarios（按业务域分组）

### US1 — 商户配置商品目录 (P1)
作为产品经理，我要上传 XML/JSON 目录，定义产品、计划、阶段、价格，以便后续创建订阅时按目录计费。
- 验收：目录校验通过、版本生效、订阅能匹配到对应 Plan。

### US2 — 用户创建订阅 (P1)
作为终端用户，我要订阅一个 SaaS 计划，系统应创建权益、生成未来发票、并在续期时自动计费。
- 验收：订阅创建成功、entitlement 状态正确、invoice 按 billing period 生成。

### US3 — 系统按用量计费 (P1)
作为使用量计费业务，我要上报原始用量记录，系统在 invoicing 时按 tiered/volume 价格模型计费。
- 验收：usage record 可写入、invoice 按用量价格项生成。

### US4 — 用户完成支付 (P1)
作为商户，我要通过支付网关（Stripe/Adyen 等）完成一笔授权/购买/退款，系统应记录支付尝试与交易。
- 验收：payment API 调用经 router 分发到插件、transaction 持久化、失败可重试。

### US5 — 发票自动流转 (P1)
作为财务人员，我要系统在订阅周期到达时自动生成并投递发票，支付成功后核销。
- 验收：invoice 生成事件触发、与 payment 关联、状态转换正确。

### US6 — 插件作者扩展支付网关 (P1)
作为插件作者，我要实现 `PaymentPluginApi` 并打包成 fat jar + `plugin.yaml`，由 LPR 加载并与 Core 交互。
- 验收：插件被加载、注册服务、Core 通过 registry 调用到实现。

### US7 — 运维人员无空窗升级插件 (P1)
作为运维人员，我要在不停止计费服务的情况下升级支付/税务插件。
- 验收：新版本启动、服务原子切换、旧版本停止；按 pluginId 查询始终返回可用实现。

### US8 — SRE 排查生产问题 (P2)
作为 SRE，我要看到租户级/插件级/支付链路的状态、日志与指标，以便定位故障。
- 验收：JAX-RS admin/health/metrics 端点、插件状态机、审计日志可查询。

### US9 — 安全管理员配置多租户访问 (P2)
作为安全管理员，我要为每个租户配置独立用户/角色/权限，防止租户 A 看到租户 B 的数据。
- 验收：Shiro realm 验证租户、RBAC 注解拦截、审计日志记录敏感操作。

### Edge Cases
- 插件 `start()` 抛异常：进入 FAILED，不影响其他插件启动（除非被配置为 mandatory）。
- 目录配置错误：校验失败，系统拒绝加载并返回可定位错误。
- 支付插件超时/失败：transaction 记录失败状态，Core 按控制插件策略重试或标记。
- 多节点部署：文件系统布局 + 节点命令分发，一致性靠运维/外部存储保证（当前版本假设所有节点可访问同一插件目录）。

## Functional Requirements

### 业务域

- **FR-BUS-001**：系统 SHALL 提供账户（Account）与租户（Tenant）的 CRUD 及按租户隔离的数据访问。
- **FR-BUS-002**：系统 SHALL 支持商品目录（Catalog）版本化、计划（Plan）与价格表（PriceList）定义，以及价格覆盖（Price Override）。
- **FR-BUS-003**：系统 SHALL 支持订阅创建、变更、取消、暂停/恢复，并驱动权益（Entitlement）与计费（Billing）状态同步。
- **FR-BUS-004**：系统 SHALL 支持用量记录写入与按用量模型（tiered/volume）计费。
- **FR-BUS-005**：系统 SHALL 按计费周期自动生成发票，支持贷项、调整、部分支付与核销。
- **FR-BUS-006**：系统 SHALL 通过 Payment Plugin 完成授权、捕获、购买、退款、信用等交易类型，并持久化支付尝试。
- **FR-BUS-007**：系统 SHALL 支持 Payment Control Plugin 对支付路由、重试策略、自动退款进行控制。
- **FR-BUS-008**：系统 SHALL 支持逾期规则配置与自动催缴状态流转。
- **FR-BUS-009**：系统 SHALL 提供持久化通知队列（Notification Queue）与事件总线（Persistent Bus），保证事件至少被投递一次。

### 插件运行时域

- **FR-PLG-001**：系统 SHALL 通过 `plugin.yaml` 显式声明的入口类加载插件，而不是扫描字节码推断。
- **FR-PLG-002**：系统 SHALL 保证插件依赖（child-first）与平台共享 API（parent-first）的 ClassLoader 隔离策略。
- **FR-PLG-003**：系统 SHALL 在 Core service 启动前完成插件启动（`START_PLUGIN` 属 `STARTUP_PRE`）。
- **FR-PLG-004**：系统 SHALL 按 `(pluginId, version)` 作用域管理服务注册，支持无空窗升级。
- **FR-PLG-005**：系统 MUST NOT 让插件 `start()` 直接调用未就绪的 Core API；插件 MUST 通过 `.../lifecycle/STARTED` 事件执行业务初始化。
- **FR-PLG-006**：系统 SHALL 在插件停止时按顺序撤销服务 → 调 `Plugin.stop()` → 释放资源 → 关闭 ClassLoader → 丢弃引用。
- **FR-PLG-007**：系统 SHALL 提供进程内 publish/subscribe EventBus，插件可订阅平台事件。
- **FR-PLG-008**：系统 SHALL 支持插件版本的热更新与回滚，安装、激活、启动三态分离。
- **FR-PLG-009**：系统 MUST NOT 在 Core 代码或 pom 中出现对具体插件的依赖。

### 平台与运维域

- **FR-OPS-001**：系统 SHALL 暴露 JAX-RS REST API，覆盖账户、订阅、目录、发票、支付、插件、管理等资源。
- **FR-OPS-002**：系统 SHALL 提供基于 Shiro 的认证、RBAC 授权、租户隔离与审计日志。
- **FR-OPS-003**：系统 SHALL 输出 Micrometer 指标与 healthcheck 端点。
- **FR-OPS-004**：系统 SHALL 支持节点命令（Node Command）用于插件安装、启停与集群分发。

## Key Entities

- **Account / Tenant**：账户与租户，多租户隔离根基。
- **Catalog / Plan / PlanPhase / PriceList**：商品目录与定价。
- **Subscription / Bundle / Entitlement**：订阅与权益。
- **UsageRecord**：用量记录。
- **Invoice / InvoiceItem / InvoicePayment**：发票与支付关联。
- **Payment / PaymentTransaction / PaymentAttempt**：支付与交易。
- **OverdueState / OverdueConfig**：逾期状态与配置。
- **NotificationQueue / PersistentBus**：异步通知与事件投递。
- **Plugin**：插件入口契约（LPR）。
- **PluginContext**：插件能触及的唯一窗口（LPR）。
- **PluginRuntime**：运行时对一个插件版本的全部状态（LPR）。
- **DefaultServiceRegistry**：服务注册中心，按类型与 `(pluginId, version)` 组织（LPR）。
- **EventBus / DefaultEventBus**：进程内事件总线（LPR）。
- **ResourceRegistry**：托管插件资源（LPR）。

## Success Criteria（可度量）

- 全量构建时间 ≤ 5 分钟（本机 73 模块，依赖已缓存，`-T 1C`）。
- OSGi/Felix 痕迹清零：`org.osgi` / Felix / `maven-bundle-plugin` / `packaging=bundle` 出现次数为 0。
- 迁移插件测试覆盖：16 个已迁移插件的单元/集成测试通过。
- ClassLoader 可卸载：插件 stop → 丢弃引用 → GC 后 `WeakReference<ClassLoader>.get() == null`。
- 无空窗升级：升级过程中按 `pluginId` 查询始终返回非空服务。
- 核心业务流程测试：订阅-发票-支付主路径集成测试通过。

## Assumptions

- 默认插件为 Java fat jar，在宿主 JVM 内执行；可选 `lpr-process` 研究适配器使用独立 JVM。
- 插件目录可被所有 Kill Bill 节点访问（当前版本），或外部工具负责分发。
- 插件作者是可信的；LPR 不防御恶意代码。
- JDK 21 运行时环境。

## Out of Scope（当前版本）

- 进程外插件的生产装配、完整支付协议与 supervisor（Unix socket PoC 已实现）。
- 插件自动依赖图解析与版本协商。
- 跨区域插件分发与外部分布式存储后端（已有共享 FS / 集群状态视图）。
- JVM / OS 安全沙箱（已有可选 JAR 签名/PKIX 验证）。

## 决策记录

| # | 问题 | 决定 | 理由 |
|---|---|---|---|
| 1 | 插件运行时复用 SOFAArk 还是自研？ | 自研 LPR | SOFAArk 的 export/import 与要替换的 OSGi 同构；Kill Bill 只需要固定 parent-first 白名单。 |
| 2 | EventBus 实现归属？ | `lpr-event-default` 同步内存 adapter，平台可选 PersistentBus | Core 只依赖契约；持久化保持显式选择，不改变生命周期同步分发。 |
| 3 | `org.killbill.billing.plugin.*` 走 parent-first 还是 child-first？ | child-first | 插件实现代码惯用此包；平台侧插件 API 改放到 `org.killbill.billing.runtime.api` 等包。 |
| 4 | 插件入口推断还是显式声明？ | 显式 `plugin.yaml` | 可 grep、可 review、失败位置靠近原因。 |
| 5 | 服务注册撤销粒度？ | 按 `(pluginId, version)` | 无空窗升级时新旧版本共享 pluginId，按 pluginId 撤销会误撤新版本。 |
| 6 | 业务核心是否随 LPR 重写？ | 不改动 | 项目目标只是替换插件运行时，业务逻辑、数据模型、公开 API 保持与上游 Kill Bill 一致。 |
