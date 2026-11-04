# Change Tasks: LPR Production Readiness Enhancements

> 本任务单从 `proposal.md` 拆解而来。每个任务完成后 MUST 更新本文件状态，并同步更新 `spec/` 中对应 capability 的「实现与验证对照」表。

## 图例

- `[ ]` pending
- `[-]` in progress
- `[x]` completed

---

## P0 — 可观测性 + 故障隔离（先做）

### T1: LPR Micrometer 指标
- **Status**: `[x]` completed
- **Acceptance Criteria**:
  - `/metrics` 暴露插件状态分布、ClassLoader 数、provider 数、start/stop 耗时、event bus 发布/失败、ResourceRegistry 资源数。
  - `lpr-core` 不直接依赖 Micrometer（保持 A1），通过 `LprMetrics` SPI 注入。
  - 新增 `TestLprMetricsCollector`（lpr-core）与 `TestKillBillLprMetrics`（plugin-runtime）验证指标输出。
- **Affected Specs**: `capability-metrics-health.md`, `capability-plugin-lifecycle.md`, `capability-service-registry.md`, `capability-event-bus.md`, `capability-resource-management.md`
- **Notes**: 已实现 `LprMetrics` port（lpr-spi）、`NoOpLprMetrics` + `LprMetricsCollector`（lpr-core）、`KillBillLprMetrics` adapter（plugin-runtime）。指标通过 Kill Bill `MetricRegistry` 输出（底层 Micrometer/Dropwizard）。未包含 Metaspace 占用（需 JVM 级埋点，归入后续可观测性增强）。

### T2: 插件调用超时 + 熔断
- **Status**: `[x]` completed
- **Acceptance Criteria**:
  - 所有 `*PluginApi` 调用有全局超时（默认 30s），支持按 pluginId 覆盖。
  - 连续失败 N 次后触发熔断，返回明确异常。
  - 故意构造死循环插件的测试：Core 不被拖死。
- **Affected Specs**: `capability-payments.md`, `capability-service-registry.md`, `capability-metrics-health.md`
- **Notes**:
  - 实现：`PluginInvocationGuard`（超时+熔断）+ `PluginServiceCircuitBreakerProxy`（动态代理包装）+ `PluginRuntimeConfig` 配置项。
  - 测试：`TestPluginInvocationGuard`、`TestPluginServiceCircuitBreakerProxy`。
  - 配置项见 `PluginRuntimeConfig` 与插件开发文档；新增全局 worker/单插件并发上限。
  - 审查修复：受检异常原样透传；熔断按代次 CAS；不合作任务占用额度直到实际退出。

---

## P1 — 运维自动化 + 健康 + 一致性

### T3: 插件健康检查接口
- **Status**: `[x]` completed
- **Acceptance Criteria**:
  - 新增 `PluginHealthCheck` 接口（可选实现）。
  - 运行时周期调用，结果影响 selector 可见性。
  - 接入 Kill Bill healthcheck 端点。
- **Affected Specs**: `capability-metrics-health.md`, `capability-service-registry.md`
- **Notes**:
  - 实现：`PluginHealthCheck` (lpr-api) + `Plugin#healthCheck()` default 方法；`PluginHealthCheckManager` 周期执行并缓存状态；`DefaultServiceRegistry.setHealthPredicate` 过滤 unhealthy 版本；`KillbillPluginHealthCheckAdapter` 桥接到 Kill Bill `Healthcheck` 注册表。
  - 配置项：`org.killbill.billing.plugin.healthcheck.enabled` / `interval` / `timeout`。
  - 测试：`TestPluginHealthCheckManager`、`TestKillbillPluginHealthCheckAdapter`、`TestDefaultServiceRegistryHealthFiltering`、`TestKillbillPluginServiceBridge#testHealthCheckIsRoutedToTheHostRegistry`。

### T4: 热升级自动回滚
- **Status**: `[x]` completed
- **Acceptance Criteria**:
  - `activateVersion` 后自动 health check，N 秒内失败自动 rollback。
  - 记录 upgrade/rollback 审计日志。
  - 端到端测试验证失败回滚无空窗。
- **Affected Specs**: `capability-version-management.md`, `capability-metrics-health.md`
- **Notes**: 
  - 实现：`PluginActivationListener` (lpr-core) + `PluginUpgradeMonitor` (plugin-runtime) + `PluginActivationEvent` 审计事件 + `PluginRepository.setActiveVersion(..., previousVersion)`。
  - 配置项：`org.killbill.billing.plugin.upgrade.autoRollback` / `healthCheck.window` / `healthCheck.interval`。
  - 测试：`TestPluginUpgradeAutoRollbackEndToEnd`。
  - 审查修复：完整窗口内保留旧实例，延迟失败切回旧版；过期 watch 不得影响后续版本，审计保留失败原因。
  - 修复了 T3 遗留的 `PluginHealthCheckManager` 重复实例问题：现在由 Guice 单例注入并在 `providePluginManager` 中复用。

### T5: ABI / 依赖作用域静态校验
- **Status**: `[x]` completed
- **Acceptance Criteria**:
  - 扫描插件 jar，检测服务方法签名中的第三方类型是否 `provided`。
  - 检测重复第三方依赖。
  - 集成到 CI，错误时构建失败。
- **Affected Specs**: `capability-classloader-isolation.md`
- **Notes**: 独立 CLI 校验直接、泛型、异常、继承契约与重复类；缺输入失败。parent 在 prepare-package 生成 classpath、verify 执行门禁；根 CI 配置全量构建及回归。详见 [validation.md](validation.md)。

### T6: 多节点插件状态一致性
- **Status**: `[x]` completed
- **Owner**: TBD
- **Acceptance Criteria**:
  - `PluginRepository` 抽象为接口。
  - 提供本地 FS / S3 / NFS / 分布式 KV 至少两种实现。
  - 提供集群级状态查询 API。
- **Affected Specs**: `capability-plugin-installation.md`
- **Notes**: Repository SPI、本地/共享 FS、跨进程 CAS、节点内容缓存、周期协调及集群 API 已验收；94 项目全量构建、22 个 ABI 门禁、224 项目标回归和 13 项负例守卫通过。真实 NFS / DB 集成未验证，详见 [t6-validation.md](t6-validation.md)。

---

## P2 — 长期架构

### T7: Service Registry API 版本协商
- **Status**: `[x]` completed
- **Owner**: TBD
- **Acceptance Criteria**:
  - 注册时附带接口版本元数据。
  - Core 调用前做兼容性检查。
- **Affected Specs**: `capability-service-registry.md`
- **Notes**: 独立接口版本、兼容性 lookup、统一宿主要求和普通/单值/健康检查桥接已实现；94 项目全量构建、22 个 ABI 门禁、268 项目标回归、18 项负例守卫通过。旧注册默认 1.0.0；详见 [t7-validation.md](t7-validation.md)。

### T8: Event Bus 背压与持久化
- **Status**: `[ ]` pending
- **Owner**: TBD
- **Acceptance Criteria**:
  - 插件事件可选择走 Kill Bill `PersistentBus`。
  - 支持重试与死信队列。
  - 纯内存 EventBus 保留为轻量选项。
- **Affected Specs**: `capability-event-bus.md`, `capability-notifications-queues.md`
- **Notes**: 

### T9: 插件安装安全校验升级
- **Status**: `[x]` completed
- **Owner**: TBD
- **Acceptance Criteria**:
  - SHA-1 替换为 SHA-256 或更强。
  - 可选 jar 签名/证书链校验。
  - 记录插件来源审计。
- **Affected Specs**: `capability-plugin-installation.md`
- **Notes**: SHA-256、可选完整 JAR 签名/PKIX、来源脱敏文件审计及资源大小/外部类路径守卫已验收；94 项目全量构建、22 个 ABI 门禁、252 项目标回归与 22 项负例守卫通过。SHA-1 API/参数为破坏性迁移，详见 [t9-validation.md](t9-validation.md)。

### T10: 进程外插件模型（研究项）
- **Status**: `[ ]` pending
- **Owner**: TBD
- **Acceptance Criteria**:
  - PoC 验证高风险插件（如支付）运行在独立进程。
  - Core 通过 gRPC/Unix socket 调用，进程崩溃不影响 Core。
- **Affected Specs**: `capability-platform-bridge.md`
- **Notes**: 不阻塞 P0/P1

### T11: 混沌/故障注入测试
- **Status**: `[x]` completed
- **Owner**: TBD
- **Acceptance Criteria**:
  - 插件 `start()` 抛 `OutOfMemoryError` 时 Core 存活。
  - 插件 `stop()` 死锁时 stop 超时并继续。
  - 并发 install + activate + rollback 不破坏状态机。
- **Affected Specs**: `capability-plugin-lifecycle.md`, `capability-resource-management.md`
- **Notes**: 故障注入、停止执行器 SPI/adapter、超时版本隔离、卸载保护及并发操作已验收；详见 [t11-validation.md](t11-validation.md)。OOM 为显式 Error 注入，不代表真实全局内存耗尽下的进程存活保证。

---

## T7 执行清单

- [x] 公共版本契约、注册元数据与兼容性 lookup
- [x] 平台配置、统一要求与三类业务桥接
- [x] 真实 ClassLoader 端到端及新增守卫变异验证
- [x] 全量构建、回归、规格同步与提交

## T9 执行清单

- [x] SHA-256 契约、命令迁移及旧参数拒绝
- [x] 完整 JAR 签名、证书信任及 descriptor 一致性校验
- [x] 来源脱敏审计 SPI、文件 adapter、失败边界
- [x] 平台配置与装配、真实签名及端到端测试
- [x] 负例守卫、全量构建、回归、文档与提交

## T6 执行清单

- [x] Repository SPI 与 deployment / selection 契约，迁出 Core 文件实现
- [x] 本地 FS + 共享 FS 适配、节点独立缓存、跨进程 CAS
- [x] manager 的修订号保护、目标状态协调与自动回滚防旧写
- [x] 平台配置、周期协调、集群状态 API / REST
- [x] 双节点 / 跨进程 / 故障与负例测试，全量构建

## T11 执行清单

- [x] 插件级 Error / OOM 启动清理与其他插件存活
- [x] SPI + 有界停止 adapter、平台配置及 TestKit 装配
- [x] 超时 STOPPING 隔离、延迟清理与同版本重载/卸载保护
- [x] 并发安装 / 发现 / 激活 / 回滚端到端故障注入
- [x] 负例守卫、隔离/卸载/架构测试及全量构建

## 完成后归档

所有任务完成后，把本目录下的 `proposal.md` + `tasks.md` 移动到 `changes/archive/lpr-production-readiness/ARCHIVED.md`（按 SDD 归档格式合并），并更新 `spec/` 中相关 capability 的对照表。

## 审查修复与验收

- [x] 修复异常透传、有界调用资源与并发熔断
- [x] 修复健康路由、调度异常隔离与运行视图竞态
- [x] 保留旧版覆盖完整观察窗口，验证升级/回滚无空窗
- [x] 修复 ABI 漏检、缺输入放行及真实插件依赖布局
- [x] 15 项负例守卫检出；还原后 185 项测试通过；92 个 reactor 项目全量构建通过、22 个插件 ABI 门禁通过
