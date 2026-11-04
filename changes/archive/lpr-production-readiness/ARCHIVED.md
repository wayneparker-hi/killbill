# 归档：LPR Production Readiness

- **状态**：T1–T11 按验收范围完成
- **归档日期**：2026-11-04
- **当前系统规范**：[spec/README.md](../../../spec/README.md)
- **最终验收**：[t10-validation.md](t10-validation.md)；95 reactor 项目、22 ABI 门禁、307 项目标回归与 T10 的 24 项负例守卫通过。

## 交付与边界

- 指标、调用超时/熔断、健康路由、升级回滚、ABI 门禁、共享 FS/集群视图、服务接口版本、持久化事件、安装安全及故障注入均已交付。
- 进程外插件为显式选择的 Unix socket / 独立 JVM 研究 PoC；授权代理只在测试中使用，没有默认生产装配或真实支付网关。
- 实际环境、跳过项及局限以各阶段验证报告为准；完成任务清单不构成所有生产环境的认证。
- 本归档保留原始提案和各阶段报告的历史表述；当前行为以系统规范及最新验证记录为准。

## Change Proposal: LPR Production Readiness Enhancements

> 把上一轮对 LPR 的工业/生产级评审落地为可跟踪、可验收的演进项。
> 本提案针对 Kill Bill 的插件运行时子系统（LPR），目标是在不破坏现有正确性的前提下补齐可观测性、故障隔离、运维自动化的短板。

### Summary

LPR 的核心正确性（状态机、停止顺序、版本作用域、ClassLoader 策略、ResourceRegistry）已经扎实，但缺少生产环境必需的：

1. 运行时自身指标（Micrometer）。
2. 插件调用超时与熔断（故障隔离）。
3. 插件健康检查与就绪探针。
4. 热升级自动回滚与审计日志。
5. ABI / 依赖作用域静态校验。
6. Service Registry API 版本协商。
7. Event Bus 背压与持久化。
8. 插件安装安全校验（签名、摘要算法升级）。
9. 多节点插件状态一致性。
10. 混沌/故障注入测试。

本提案按优先级拆分为 P0/P1/P2 三档，便于分批交付。

### Motivation

- 当前生产排障靠日志和猜测，没有数据。
- 单个插件死锁/OOM/StackOverflow 会把整个 Kill Bill JVM 拉下水。
- 插件升级后若异常，需要人工发现并回滚。
- 插件作者容易写错依赖作用域，导致运行期 `ClassCastException`。
- 多节点部署时插件目录与 ACTIVE marker 是本地文件，缺乏集群一致视图。

### Goals

- P0：LPR 可观测性指标 + 插件调用超时/熔断，使生产问题可量化、单插件故障不扩散。
- P1：健康检查、自动回滚、ABI 静态检查、集群状态一致性，降低运维风险。
- P2：进程外插件模型、jar 签名、API 版本协商、事件持久化与混沌测试，作为长期架构演进。

### Non-Goals

- 改动 Kill Bill 业务核心（账户、订阅、发票、支付逻辑）。
- 重写 LPR 的状态机、ClassLoader 策略、版本作用域等已验证正确部分。
- 一次性完成所有 P2 项。

### Proposed Changes

#### P0 — 可观测性 + 故障隔离

1. **LPR Micrometer 指标**
   - 插件状态机分布（按 pluginId/version/state）。
   - ClassLoader 数量与 Metaspace 占用。
   - Service registry 按类型的 provider 数。
   - `Plugin.start()` / `Plugin.stop()` 耗时直方图。
   - Event bus 发布次数、handler 失败率。
   - ResourceRegistry 托管资源数。
   - 实现位置：`killbill-platform/plugin-runtime` 新增指标适配；`lpr-core` 通过 `LprMetrics` SPI 注入（不直接依赖 Micrometer，保持 A1）。

2. **插件调用超时 + 熔断**
   - 对插件实现的所有 `*PluginApi` 调用加全局/按插件超时。
   - 连续失败 N 次后熔断，返回降级或错误。
   - 实现位置：`killbill-platform/plugin-runtime` 桥接层增加 `PluginInvocationGuard` / `PluginServiceCircuitBreakerProxy`。

#### P1 — 运维自动化 + 健康 + 一致性

3. **插件健康检查**
   - 新增 `PluginHealthCheck` 接口，运行时周期调用。
   - `ACTIVE` 细分为 healthy/unhealthy；不健康时从 selector 暂时移除。
   - 接入 Kill Bill 现有 healthcheck 端点。

4. **热升级自动回滚**
   - `activateVersion` 启动新版本后自动 health check，N 秒内失败则 rollback。
   - 记录 upgrade/rollback 审计日志（who/when/from/to/result）。

5. **ABI / 依赖作用域静态校验**
   - 在独立 `lpr-abi-validator` CLI 中扫描插件 jar，并由 parent verify profile 执行：
     - 出现在服务方法签名里的第三方类型是否声明为 `provided`。
     - 是否存在重复的第三方依赖。
   - 失败即 CI 报错。

6. **多节点插件状态一致性**
   - 抽象 `PluginRepository` 为接口：本地 FS / S3 / NFS / 分布式 KV。
   - ACTIVE marker 与插件目录布局支持共享存储或外部同步。
   - 提供集群级状态查询 API。

#### P2 — 长期架构

7. **Service Registry API 版本协商**
   - 注册时附带接口版本。
   - Core 调用前做兼容性检查。

8. **Event Bus 背压与持久化**
   - 让插件事件走 Kill Bill 已有 `PersistentBus`，替代纯内存 `DefaultEventBus`。
   - 支持重试与死信队列。

9. **插件安装安全校验升级**
   - SHA-256 或更强摘要替换 SHA-1。
   - 可选 jar 签名/证书链校验。
   - 记录插件来源审计。

10. **进程外插件模型（研究项）**
    - 参考 HashiCorp go-plugin，评估把高风险插件（支付网关）放到独立进程的可行性。
    - 通过本地 gRPC/Unix socket 与 Core 通信。

11. **混沌/故障注入测试**
    - 插件 `start()` 抛 `OutOfMemoryError` 时 Core 是否存活。
    - 插件 `stop()` 死锁时 stop 是否超时。
    - 并发 install + activate + rollback。

### Acceptance Criteria

- P0 完成后：
  - `/metrics` 能看到 LPR 相关指标。
  - 故意写一个死循环 Payment 插件，调用超时不把 Core 拖死。
- P1 完成后：
  - 插件 unhealthy 时从 selector 移除， healthy 后恢复。
  - 升级失败 30 秒内自动 rollback 且过程中无服务空窗。
  - CI 能检测出依赖作用域写错的插件 jar。
- P2 完成后：
  - 高风险插件可运行在独立进程（PoC 级）。
  - 插件事件走持久化总线。

### Risks

| 风险 | 缓解 |
|---|---|
| 超时配置不当导致正常慢插件被误杀 | 默认值保守，支持按插件覆盖 |
| 健康检查调用本身拖垮插件 | 异步、可配置间隔、异常隔离 |
| 持久化 Event Bus 改变事件语义 | 保留纯内存总线作为可选，渐进迁移 |
| 进程外模型改动大 | 作为独立研究项，不阻塞 P0/P1 |

### Open Questions

1. 超时/熔断的默认阈值是否按插件类型区分（支付 vs 通知）？
2. 健康检查接口是否强制所有插件实现，还是仅可选？
3. 多节点一致性是否必须依赖数据库，还是允许 S3/NFS 配置？

### References

- LPR 设计文档：`docs/design/lightweight-plugin-runtime.md`
- 架构约束：`spec/constitution.md`

---

## Change Tasks: LPR Production Readiness Enhancements

> 本任务单从本归档保留的原始提案拆解而来。每个任务完成后 MUST 更新本文件状态，并同步更新 `spec/` 中对应 capability 的「实现与验证对照」表。

### 图例

- `[ ]` pending
- `[-]` in progress
- `[x]` completed

---

### P0 — 可观测性 + 故障隔离（先做）

#### T1: LPR Micrometer 指标
- **Status**: `[x]` completed
- **Acceptance Criteria**:
  - `/metrics` 暴露插件状态分布、ClassLoader 数、provider 数、start/stop 耗时、event bus 发布/失败、ResourceRegistry 资源数。
  - `lpr-core` 不直接依赖 Micrometer（保持 A1），通过 `LprMetrics` SPI 注入。
  - 新增 `TestLprMetricsCollector`（lpr-core）与 `TestKillBillLprMetrics`（plugin-runtime）验证指标输出。
- **Affected Specs**: `capability-metrics-health.md`, `capability-plugin-lifecycle.md`, `capability-service-registry.md`, `capability-event-bus.md`, `capability-resource-management.md`
- **Notes**: 已实现 `LprMetrics` port（lpr-spi）、`NoOpLprMetrics` + `LprMetricsCollector`（lpr-core）、`KillBillLprMetrics` adapter（plugin-runtime）。指标通过 Kill Bill `MetricRegistry` 输出（底层 Micrometer/Dropwizard）。未包含 Metaspace 占用（需 JVM 级埋点，归入后续可观测性增强）。

#### T2: 插件调用超时 + 熔断
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

### P1 — 运维自动化 + 健康 + 一致性

#### T3: 插件健康检查接口
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

#### T4: 热升级自动回滚
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

#### T5: ABI / 依赖作用域静态校验
- **Status**: `[x]` completed
- **Acceptance Criteria**:
  - 扫描插件 jar，检测服务方法签名中的第三方类型是否 `provided`。
  - 检测重复第三方依赖。
  - 集成到 CI，错误时构建失败。
- **Affected Specs**: `capability-classloader-isolation.md`
- **Notes**: 独立 CLI 校验直接、泛型、异常、继承契约与重复类；缺输入失败。parent 在 prepare-package 生成 classpath、verify 执行门禁；根 CI 配置全量构建及回归。详见 [validation.md](validation.md)。

#### T6: 多节点插件状态一致性
- **Status**: `[x]` completed
- **Owner**: TBD
- **Acceptance Criteria**:
  - `PluginRepository` 抽象为接口。
  - 提供本地 FS / S3 / NFS / 分布式 KV 至少两种实现。
  - 提供集群级状态查询 API。
- **Affected Specs**: `capability-plugin-installation.md`
- **Notes**: Repository SPI、本地/共享 FS、跨进程 CAS、节点内容缓存、周期协调及集群 API 已验收；94 项目全量构建、22 个 ABI 门禁、224 项目标回归和 13 项负例守卫通过。真实 NFS / DB 集成未验证，详见 [t6-validation.md](t6-validation.md)。

---

### P2 — 长期架构

#### T7: Service Registry API 版本协商
- **Status**: `[x]` completed
- **Owner**: TBD
- **Acceptance Criteria**:
  - 注册时附带接口版本元数据。
  - Core 调用前做兼容性检查。
- **Affected Specs**: `capability-service-registry.md`
- **Notes**: 独立接口版本、兼容性 lookup、统一宿主要求和普通/单值/健康检查桥接已实现；94 项目全量构建、22 个 ABI 门禁、268 项目标回归、18 项负例守卫通过。旧注册默认 1.0.0；详见 [t7-validation.md](t7-validation.md)。

#### T8: Event Bus 背压与持久化
- **Status**: `[x]` completed
- **Owner**: TBD
- **Acceptance Criteria**:
  - 插件事件可选择走 Kill Bill `PersistentBus`。
  - 支持重试与死信队列。
  - 纯内存 EventBus 保留为轻量选项。
- **Affected Specs**: `capability-event-bus.md`, `capability-notifications-queues.md`
- **Notes**: JDK-only 持久化契约、严格数据库确认、平台固定信封、并发准入、版本路由及自动资源释放已完成；真实 H2 文件数据库恢复/重试/FAILED、ClassLoader/GC 与 Guice 装配验证通过。94 项目构建、22 ABI 门禁、284 项回归和 20 项变异守卫通过。默认关闭，要求全部 externalBus 消费节点一致启用；详见 [t8-validation.md](t8-validation.md)。

#### T9: 插件安装安全校验升级
- **Status**: `[x]` completed
- **Owner**: TBD
- **Acceptance Criteria**:
  - SHA-1 替换为 SHA-256 或更强。
  - 可选 jar 签名/证书链校验。
  - 记录插件来源审计。
- **Affected Specs**: `capability-plugin-installation.md`
- **Notes**: SHA-256、可选完整 JAR 签名/PKIX、来源脱敏文件审计及资源大小/外部类路径守卫已验收；94 项目全量构建、22 个 ABI 门禁、252 项目标回归与 22 项负例守卫通过。SHA-1 API/参数为破坏性迁移，详见 [t9-validation.md](t9-validation.md)。

#### T10: 进程外插件模型（研究项）
- **Status**: `[x]` completed
- **Owner**: TBD
- **Acceptance Criteria**:
  - PoC 验证高风险插件（如支付）运行在独立进程。
  - Core 通过 gRPC/Unix socket 调用，进程崩溃不影响 Core。
- **Affected Specs**: `capability-platform-bridge.md`
- **Notes**: 独立 JVM / Unix socket 研究 adapter、JDK-only 契约、支付授权 typed registry PoC 已完成；95 项目构建、22 ABI 门禁、307 项回归和 24 项负例守卫通过。不接入默认生产装配；详见 [t10-validation.md](t10-validation.md)。

#### T11: 混沌/故障注入测试
- **Status**: `[x]` completed
- **Owner**: TBD
- **Acceptance Criteria**:
  - 插件 `start()` 抛 `OutOfMemoryError` 时 Core 存活。
  - 插件 `stop()` 死锁时 stop 超时并继续。
  - 并发 install + activate + rollback 不破坏状态机。
- **Affected Specs**: `capability-plugin-lifecycle.md`, `capability-resource-management.md`
- **Notes**: 故障注入、停止执行器 SPI/adapter、超时版本隔离、卸载保护及并发操作已验收；详见 [t11-validation.md](t11-validation.md)。OOM 为显式 Error 注入，不代表真实全局内存耗尽下的进程存活保证。

---

### T10 执行清单

- [x] 全量基线、PoC 范围、协议与失败语义设计
- [x] JDK-only 契约、Unix socket / 独立 JVM adapter
- [x] 支付 typed registry、崩溃 / 超时 / 有界资源验证
- [x] 守卫变异、全量构建、回归、规格同步与归档

### T8 执行清单

- [x] 全量构建基线、持久化边界与接口设计
- [x] 公共契约、严格写入、平台适配与配置
- [x] 持久化重启/重试/死信、背压、版本路由及卸载验证
- [x] 负例守卫、全量构建、回归、文档与提交

### T7 执行清单

- [x] 公共版本契约、注册元数据与兼容性 lookup
- [x] 平台配置、统一要求与三类业务桥接
- [x] 真实 ClassLoader 端到端及新增守卫变异验证
- [x] 全量构建、回归、规格同步与提交

### T9 执行清单

- [x] SHA-256 契约、命令迁移及旧参数拒绝
- [x] 完整 JAR 签名、证书信任及 descriptor 一致性校验
- [x] 来源脱敏审计 SPI、文件 adapter、失败边界
- [x] 平台配置与装配、真实签名及端到端测试
- [x] 负例守卫、全量构建、回归、文档与提交

### T6 执行清单

- [x] Repository SPI 与 deployment / selection 契约，迁出 Core 文件实现
- [x] 本地 FS + 共享 FS 适配、节点独立缓存、跨进程 CAS
- [x] manager 的修订号保护、目标状态协调与自动回滚防旧写
- [x] 平台配置、周期协调、集群状态 API / REST
- [x] 双节点 / 跨进程 / 故障与负例测试，全量构建

### T11 执行清单

- [x] 插件级 Error / OOM 启动清理与其他插件存活
- [x] SPI + 有界停止 adapter、平台配置及 TestKit 装配
- [x] 超时 STOPPING 隔离、延迟清理与同版本重载/卸载保护
- [x] 并发安装 / 发现 / 激活 / 回滚端到端故障注入
- [x] 负例守卫、隔离/卸载/架构测试及全量构建

### 完成后归档

2026-11-04：所有任务完成，提案与任务合并至本归档；设计、验证报告保留在同目录，相关系统规格已同步。

### 审查修复与验收

- [x] 修复异常透传、有界调用资源与并发熔断
- [x] 修复健康路由、调度异常隔离与运行视图竞态
- [x] 保留旧版覆盖完整观察窗口，验证升级/回滚无空窗
- [x] 修复 ABI 漏检、缺输入放行及真实插件依赖布局
- [x] 15 项负例守卫检出；还原后 185 项测试通过；92 个 reactor 项目全量构建通过、22 个插件 ABI 门禁通过
