# spec/ — 系统真相源

> 按 [SDD Kit](../spec/constitution.md) 组织的规范驱动开发真相源。
> 这里只写 **Kill Bill 整体系统当前是什么**；变更过程见 [`../changes/`](../changes/)。
> LPR 是 Kill Bill 的插件运行时子系统，不是独立项目；所有能力规格都从 Kill Bill 系统视角书写。

## 文件导航

### 顶层规格

| 文件 | 内容 |
|---|---|
| [constitution.md](constitution.md) | 工程宪法：不可违反的架构约束与 CI 门绑定 |
| [killbill.md](killbill.md) | 系统级总规格：Kill Bill 整体范围、User Scenarios、Functional Requirements、成功标准、决策记录 |

### 业务能力

| 文件 | 内容 |
|---|---|
| [capability-account-tenant.md](capability-account-tenant.md) | 账户与租户管理 |
| [capability-catalog.md](capability-catalog.md) | 商品目录与定价 |
| [capability-subscription-entitlement.md](capability-subscription-entitlement.md) | 订阅与权益生命周期 |
| [capability-usage.md](capability-usage.md) | 用量计费 |
| [capability-invoicing.md](capability-invoicing.md) | 发票生成、调整与核销 |
| [capability-payments.md](capability-payments.md) | 支付路由、插件网关、支付控制 |
| [capability-overdue.md](capability-overdue.md) | 逾期与催缴 |
| [capability-notifications-queues.md](capability-notifications-queues.md) | 持久化事件总线与通知队列 |
| [capability-security-rbac.md](capability-security-rbac.md) | 认证、RBAC 与多租户隔离 |
| [capability-jaxrs-api.md](capability-jaxrs-api.md) | JAX-REST API 与插件 HTTP 路由 |
| [capability-metrics-health.md](capability-metrics-health.md) | Micrometer 指标与健康检查 |
| [capability-release-validation.md](capability-release-validation.md) | 独立数据库与生产 WAR 发布验收 |

### 插件运行时能力（由 LPR 实现）

| 文件 | 内容 |
|---|---|
| [capability-classloader-isolation.md](capability-classloader-isolation.md) | ClassLoader 隔离 |
| [capability-plugin-lifecycle.md](capability-plugin-lifecycle.md) | 插件生命周期与状态机 |
| [capability-service-registry.md](capability-service-registry.md) | 服务注册与发现 |
| [capability-event-bus.md](capability-event-bus.md) | 同步内存 / 可选持久化事件总线 |
| [capability-resource-management.md](capability-resource-management.md) | 资源托管与 ClassLoader 可卸载性 |
| [capability-version-management.md](capability-version-management.md) | 版本管理与热更新/回滚 |
| [capability-plugin-installation.md](capability-plugin-installation.md) | 插件安装与仓库布局 |
| [capability-platform-bridge.md](capability-platform-bridge.md) | Kill Bill 平台桥接 / 进程外 PoC |

## 使用方式

- 读约束：先看 `constitution.md`。
- 读系统全貌：再看 `killbill.md`。
- 读具体能力：翻到对应 `capability-*.md`。
- 要改行为：到 [`../changes/`](../changes/) 开一个新的 delta，按 proposal → tasks → 实现 → 合并回 spec → 归档的闭环走。

## 维护规则

- 需求级行为变化 MUST 先改本目录，再改代码。
- 每条 capability 的 Scenario MUST 映射到可执行测试；新增时必须更新「实现与验证对照」表。
- 链接改名或文件移动时，同步更新本 README 与 `../docs/README.md`。
