# Capability: Plugin Lifecycle

> Kill Bill 的插件运行时能力：驱动插件在安装、启动、运行、停止、卸载之间按状态机安全转换，并保证并发下不会半启动或半停止。当前由 LPR 实现。

## Requirements

### Requirement: 状态转换由运行时 CAS 控制

只有运行时 SHALL 能改变插件状态；转换 SHALL 使用 `AtomicReference` + CAS，保证并发 `start()` 只产生一个 winner。

#### Scenario: 并发启动同一个插件
- **WHEN** 两个线程同时调用 `PluginManager.start(pluginId)`
- **THEN** 只有一个线程真正执行启动，另一个返回 false 或观察到 ACTIVE

### Requirement: 启动顺序固定

启动 SHALL 按：CAS STARTING → 构建 ClassLoader → 实例化 → 构建 Context → 调 `Plugin.start()` → ACTIVE。

#### Scenario: 正常启动插件
- **WHEN** 插件从 INSTALLED/STOPPED/FAILED 状态开始启动
- **THEN** 它依次进入 STARTING、执行 start()、进入 ACTIVE

### Requirement: 插件 start 中失败必须清理

若 `Plugin.start()` 抛出异常或 Error（含可恢复的插件级 `OutOfMemoryError`），运行时 MUST 撤销已注册服务、释放资源、关闭 ClassLoader、丢弃引用，并将状态置为 FAILED。

#### Scenario: start 抛异常
- **IF** `Plugin.start()` 抛 `RuntimeException`
- **THEN** 服务注册被撤销、ClassLoader 被关闭、状态为 FAILED；其他插件不受影响

### Requirement: 停止顺序固定

停止 SHALL 按：CAS STOPPING → 撤销服务注册 → 调 `Plugin.stop()` → 释放资源 → 关闭 ClassLoader → 丢弃引用 → STOPPED。

#### Scenario: 正常停止插件
- **WHEN** 调用 `PluginManager.stop(pluginId)`
- **THEN** 新调用者先被切断，旧调用者完成后插件再停止，最终 ClassLoader 可回收

### Requirement: 有界停止与超时隔离

`Plugin.stop()` 及其资源清理 SHALL 在 SPI 提供的有界 daemon 执行器中运行；宿主等待时间可配置。超时 SHALL 撤销服务并继续处理其他插件。仍执行中的版本 SHALL 保持 STOPPING，禁止同版本重新加载或删除 jar；回调退出后才按序释放资源、关闭 ClassLoader 并清除引用。执行器饱和 SHALL 明确拒绝操作，不创建额外 worker。

#### Scenario: stop 忽略中断并阻塞
- **WHEN** 插件 stop 等待一个永不自行释放的门闩
- **THEN** 宿主在配置期限内返回，其他插件可停止；阻塞版本为 STOPPING，服务不可见，ClassLoader 保持可用
- **AND** 测试释放门闩后，该版本完成清理并进入 STOPPED

#### Scenario: start 抛出 OutOfMemoryError
- **WHEN** 插件已注册服务和资源后主动抛出 OutOfMemoryError
- **THEN** 失败版本完整清理并进入 FAILED，其他插件仍能启动和提供服务

### Requirement: 并发安装与版本操作一致

同节点并发安装、发现、激活与回滚 SHALL 不丢失已加载实例，不重复启动同版本，不产生可见服务空窗；ACTIVE marker SHALL 与最终运行版本一致。

#### Scenario: 并发发布和版本切换
- **WHEN** 多线程安装新版本并与 activate / rollback / discover 交错执行
- **THEN** 最终选中版本、marker 与路由一致；停止所有插件后无残留服务或资源

### Requirement: 插件启动早于 Core Service

`START_PLUGIN` SHALL 属于 `STARTUP_PRE`，在 Core service 的 `START_SERVICE` 之前完成。

#### Scenario: 系统启动
- **WHEN** Kill Bill 启动
- **THEN** 插件在 Core service 启动前已完成启动

### Requirement: start 中不得调用 Core API 业务方法

插件 `start()` SHALL NOT 调用 Core API 执行业务；相关工作 MUST 在 `.../lifecycle/STARTED` 事件中完成。

#### Scenario: 插件在 STARTED 事件中初始化
- **WHEN** Core 发布 STARTED 事件
- **THEN** 插件的事件处理器才能执行业务初始化

### Requirement: 生命周期操作可观测

`Plugin.start()` 与 `Plugin.stop()` 的耗时 SHALL 被记录为指标；ClassLoader 创建与销毁 SHALL 被计数。

#### Scenario: 启动插件
- **WHEN** 插件成功启动
- **THEN** `killbill.lpr.plugin.start.duration` 记录耗时，`killbill.lpr.classloaders.active` 增加

## MODIFIED Requirements

无。

## 实现与验证对照

| Requirement | 实现（文件:行 / 组件） | 验证（测试类） |
|---|---|---|
| CAS 状态转换 | `PluginRuntime.transition` / `DefaultPluginLifecycleManager.start` | `TestDefaultPluginLifecycleManager` |
| 启动顺序 | `DefaultPluginLifecycleManager.start` | `TestDefaultPluginLifecycleManager` |
| 启动失败清理 | `DefaultPluginLifecycleManager.start catch 块` | `TestDefaultPluginLifecycleManager` |
| 插件级 Error 隔离 | `DefaultPluginLifecycleManager.start/tearDown` / `DefaultResourceRegistry.runQuietly` | `TestLifecycleFaultInjection.startupOutOfMemoryErrorDoesNotStrandServicesResourcesOrOtherPlugins`、`stopErrorStillReleasesResourcesAndLoader` |
| 有界停止与超时隔离 | `PluginStopExecutor` / `DefaultPluginStopExecutor` / `PluginServiceScope` / `DefaultPluginManager.retiringRuntimes` | `TestDefaultPluginStopExecutor`、`TestLifecycleFaultInjection`、`TestPluginNodeCommandListener.testUninstallKeepsJarWhileTimedOutStopIsStillExecuting` |
| 并发版本操作 | `DefaultPluginManager` 同节点串行化 / `PluginInstaller.install` 原子发布 | `TestConcurrentPluginOperations` |
| 停止顺序 | `DefaultPluginLifecycleManager.stop` / `cleanUp` | `TestDefaultPluginLifecycleManager` |
| START_PLUGIN 顺序 | `PluginRuntimeService` (`@LifecycleHandlerType(START_PLUGIN)`) | 全量启动测试 |
| STARTED 事件约束 | `Plugin.java` 接口注释 + `PluginContext` 注释 | 代码审查、集成测试 |
| 生命周期可观测 | `DefaultPluginLifecycleManager.start/stop` 中计时与 ClassLoader 计数 | `TestLprMetricsCollector`、`TestKillBillLprMetrics` |
