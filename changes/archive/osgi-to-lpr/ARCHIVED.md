# ARCHIVED · osgi-to-lpr（Kill Bill + LPR 迁移）

- **归档日期**：2026-10-21
- **状态**：已实现、已验证、已合并回 `spec/`
- **合并去向**：
  - `spec/killbill-lpr.md`（系统级规格与决策记录）
  - `spec/capability-classloader-isolation.md`
  - `spec/capability-plugin-lifecycle.md`
  - `spec/capability-service-registry.md`
  - `spec/capability-event-bus.md`
  - `spec/capability-resource-management.md`
  - `spec/capability-version-management.md`
  - `spec/capability-plugin-installation.md`
  - `spec/capability-platform-bridge.md`
- **背景**：仓库根目录 `CLAUDE.md`、docs/design/lightweight-plugin-runtime.md、docs/migration/osgi-to-lpr-plan.md

## 交付

- 删除 `killbill-platform/osgi` 及全部 OSGi/Felix 依赖，共 141 文件 / 16408 行。
- 新建自研轻量级插件运行时 `lpr/`（8 个模块：api、spi、core、classloader-default、descriptor-yaml、event-default、management、testkit）。
- 新建平台适配层 `killbill-platform/plugin-runtime`，用 Guice 将 LPR 接入 Kill Bill 生命周期与 13 个业务扩展点。
- 迁移插件侧 SDK `killbill-plugin-framework/`，替代 `killbill-plugin-framework-java`。
- 迁移 16 个插件到 reactor内，其余因 API 漂移或依赖不可达暂时移出。
- 官方 6 个仓库合并为单 reactor（73+ 模块）。

## 关键取舍

| 决策 | 选择 | 否掉的备选 |
|---|---|---|
| ClassLoader 后端 | 自研 `DefaultPluginClassLoader` | SOFAArk（其 export/import package 模型与 OSGi 同构，违背改造动机） |
| EventBus 是否抽 port | 留在 `lpr-core` 自研 | `lpr-event-guava` adapter（只有一个实现，抽 port 无意义） |
| `org.killbill.billing.plugin.*` | child-first | parent-first（会把插件实现类误判为平台 API） |
| 插件入口 | `plugin.yaml` 显式声明 | bnd 扫描（失败位置远离原因） |
| 服务撤销粒度 | `(pluginId, version)` | 仅 `pluginId`（会导致无空窗升级失败） |
| KPM | 精简为 `PluginInstaller` + 节点命令 | 保留完整 KPM（其中目录查询/catalog 不是运行时职责） |

## 验证

- `mvn -B -ff -T 1C clean install -DskipTests=true` 全量构建通过。
- `lpr-testkit` 隔离性与泄漏性测试通过：`TestClassLoaderIsolation`、`TestClassLoaderUnloading`。
- 端到端热升级/回滚测试通过：`TestPluginRuntimeEndToEnd`。
- Kill Bill 核心 1128 项测试全绿；插件框架 1312 项；已迁移插件 117 项。

## 未尽（后续 slice）

- 可观测性指标（Micrometer 接入）
- 插件健康检查与自动回滚
- 进程外插件模型（gRPC）
- ABI 静态检查工具
- 集群级插件状态一致性视图
