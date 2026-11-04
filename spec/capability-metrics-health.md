# Capability: Metrics & Health Checks

> Kill Bill 通过 Micrometer 输出指标，并通过 healthcheck 端点暴露关键依赖（数据库、插件运行时等）状态。

## Requirements

### Requirement: Micrometer 指标输出

系统 SHALL 输出 JVM、数据库连接池、业务操作（支付、发票、通知队列等）的指标到配置的 Micrometer registry。

#### Scenario: 支付计数
- **WHEN** 一笔支付完成
- **THEN** 对应 payment transaction 指标增加

### Requirement: Healthcheck 端点

系统 SHALL 提供 healthcheck 端点，反映数据库、插件运行时等关键组件是否健康。

#### Scenario: 数据库不可达
- **IF** 数据库连接失败
- **THEN** healthcheck 返回不健康状态

### Requirement: 插件运行时指标

LPR SHALL 暴露以下指标（通过 Kill Bill MetricRegistry/Micrometer）：
- 各状态插件数量：`killbill.lpr.plugins.state.<state>`
- 活跃 ClassLoader 数：`killbill.lpr.classloaders.active`
- 按服务类型的 provider 数：`killbill.lpr.registry.providers.<type>`
- 插件 start/stop 耗时：`killbill.lpr.plugin.start.duration` / `killbill.lpr.plugin.stop.duration`
- EventBus 发布次数与失败次数：`killbill.lpr.events.published` / `killbill.lpr.events.failed`
- ResourceRegistry 托管资源数：`killbill.lpr.resources.registered`
- 插件调用超时次数：`killbill.lpr.invocation.timeout`
- 插件执行失败次数（包括原样透传的业务异常）：`killbill.lpr.invocation.failure`
- 熔断器打开次数：`killbill.lpr.circuitbreaker.open`

#### Scenario: 插件启动失败
- **IF** 某插件进入 FAILED 状态
- **THEN** `killbill.lpr.plugins.state.failed` 反映该状态，且 start/stop timer 记录耗时

#### Scenario: 插件调用超时
- **IF** 某插件调用触发超时保护
- **THEN** `killbill.lpr.invocation.timeout` 计数增加

## MODIFIED Requirements

无。

## 实现与验证对照

| Requirement | 实现（文件:行 / 组件） | 验证（测试类） |
|---|---|---|
| Micrometer 指标 | `MetricsService` / `killbill-commons/metrics-api` | `TestKillBillLprMetrics`（LPR adapter）；全站指标端点未在本变更验收 |
| Healthcheck | `Healthcheck` 注册表（Kill Bill 平台层） | 集成测试 |
| 插件运行时指标 | `LprMetrics` port (lpr-spi) + `LprMetricsCollector` (lpr-core) + `KillBillLprMetrics` adapter (plugin-runtime) | `TestLprMetricsCollector`、`TestKillBillLprMetrics` |
| 调用保护指标 | `LprMetricsCollector.recordPluginInvocationTimeout/Failure/CircuitBreakerOpen` | `TestPluginInvocationGuard` |
| 插件健康检查 | `PluginHealthCheck` (lpr-api) + `PluginHealthCheckManager` (plugin-runtime) + `KillbillPluginHealthCheckAdapter` | `TestPluginHealthCheckManager`、`TestKillbillPluginHealthCheckAdapter` |
| 健康检查指标 | `LprMetricsCollector.recordPluginHealthCheckDuration` | `TestPluginHealthCheckManager` |
| 升级回滚指标 | `LprMetricsCollector.recordUpgradeRollback` | `TestPluginUpgradeAutoRollbackEndToEnd` |
| 启动失败释放 ClassLoader 计数 | `DefaultPluginLifecycleManager` 清理分支 | `TestDefaultPluginLifecycleManager.testFailedStartReleasesClassLoaderMetric` |
| 健康 accessor 异常/超时隔离与调度存活 | `PluginHealthCheckManager`；生命周期仅注册延迟探针 | `TestPluginHealthCheckManager`、`TestDefaultPluginLifecycleManager.testHealthAccessorIsDeferredUntilProbeExecution` |
