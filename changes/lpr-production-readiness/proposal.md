# Change Proposal: LPR Production Readiness Enhancements

> 把上一轮对 LPR 的工业/生产级评审落地为可跟踪、可验收的演进项。
> 本提案针对 Kill Bill 的插件运行时子系统（LPR），目标是在不破坏现有正确性的前提下补齐可观测性、故障隔离、运维自动化的短板。

## Summary

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

## Motivation

- 当前生产排障靠日志和猜测，没有数据。
- 单个插件死锁/OOM/StackOverflow 会把整个 Kill Bill JVM 拉下水。
- 插件升级后若异常，需要人工发现并回滚。
- 插件作者容易写错依赖作用域，导致运行期 `ClassCastException`。
- 多节点部署时插件目录与 ACTIVE marker 是本地文件，缺乏集群一致视图。

## Goals

- P0：LPR 可观测性指标 + 插件调用超时/熔断，使生产问题可量化、单插件故障不扩散。
- P1：健康检查、自动回滚、ABI 静态检查、集群状态一致性，降低运维风险。
- P2：进程外插件模型、jar 签名、API 版本协商、事件持久化与混沌测试，作为长期架构演进。

## Non-Goals

- 改动 Kill Bill 业务核心（账户、订阅、发票、支付逻辑）。
- 重写 LPR 的状态机、ClassLoader 策略、版本作用域等已验证正确部分。
- 一次性完成所有 P2 项。

## Proposed Changes

### P0 — 可观测性 + 故障隔离

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

### P1 — 运维自动化 + 健康 + 一致性

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

### P2 — 长期架构

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

## Acceptance Criteria

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

## Risks

| 风险 | 缓解 |
|---|---|
| 超时配置不当导致正常慢插件被误杀 | 默认值保守，支持按插件覆盖 |
| 健康检查调用本身拖垮插件 | 异步、可配置间隔、异常隔离 |
| 持久化 Event Bus 改变事件语义 | 保留纯内存总线作为可选，渐进迁移 |
| 进程外模型改动大 | 作为独立研究项，不阻塞 P0/P1 |

## Open Questions

1. 超时/熔断的默认阈值是否按插件类型区分（支付 vs 通知）？
2. 健康检查接口是否强制所有插件实现，还是仅可选？
3. 多节点一致性是否必须依赖数据库，还是允许 S3/NFS 配置？

## References

- LPR 设计文档：`docs/design/lightweight-plugin-runtime.md`
- 架构约束：`spec/constitution.md`
