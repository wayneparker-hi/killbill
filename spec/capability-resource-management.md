# Capability: Plugin Resource Management

> Kill Bill 的插件运行时能力：托管插件创建的线程、连接、订阅、watcher 等资源，保证插件停止后 ClassLoader 可被 GC。当前由 LPR 实现。

## Requirements

### Requirement: 资源必须注册到 ResourceRegistry

插件在 `start()` 中创建的 executor、subscription、connection、watcher 等 SHALL 通过 `PluginContext.resources().register(...)` 托管。

#### Scenario: 插件创建线程池
- **WHEN** 插件创建 `ExecutorService`
- **THEN** 它 MUST 注册到 ResourceRegistry

### Requirement: 停止时释放引用

`ResourceRegistry` 的契约 SHALL 是"释放引用"，而不仅是调用 `close()`；停止后运行时 MUST 丢弃对线程、executor、ClassLoader 的引用。

#### Scenario: 插件停止后 GC
- **WHEN** 插件进入 STOPPED 且引用被丢弃
- **THEN** `WeakReference<ClassLoader>.get()` 返回 null

### Requirement: 停止超时不得提前关闭 ClassLoader

超时返回不等于执行结束。STOPPING 版本 SHALL 保留完成回调所需的引用和 ClassLoader，禁止重载相同版本；仅在回调及资源清理结束后释放。资源清理中单个 Error SHALL 不阻断后续资源释放。仍运行的 daemon worker 受全局上限约束，JVM 内不强制终止它。

#### Scenario: 超时后仍可延迟加载类
- **WHEN** stop 超时后回调继续执行并加载一个此前未加载的插件类
- **THEN** 类加载成功；实际退出后 ClassLoader 被关闭，runtime 引用被清除

### Requirement: JDK 21 Thread 引用行为兼容

运行时 MUST 考虑 JDK 21 中已终止 Thread 仍强引用 Runnable 的事实，不能依赖旧 JDK 行为。

#### Scenario: 持有已终止线程
- **IF** 运行时仍持有已停止线程池的引用
- **THEN** ClassLoader 无法被回收

### Requirement: 启动失败也要清理资源

`Plugin.start()` 抛异常时，运行时 MUST 关闭已创建的资源并丢弃引用，不能留下半启动状态。

#### Scenario: start 中创建资源后失败
- **IF** 插件 start 中注册了 executor 然后抛异常
- **THEN** executor 被关闭、引用被丢弃、状态为 FAILED

## MODIFIED Requirements

无。

## 实现与验证对照

| Requirement | 实现（文件:行 / 组件） | 验证（测试类） |
|---|---|---|
| 资源注册 | `ResourceRegistry` / `DefaultResourceRegistry` | `TestDefaultResourceRegistry` |
| 释放引用 | `DefaultResourceRegistry.closeAll` / `PluginRuntime.unloaded` | `TestClassLoaderUnloading` |
| 超时不提前关闭 / 清除引用 | `DefaultPluginLifecycleManager.tearDown` / `PluginRuntime.unloaded` | `TestLifecycleFaultInjection.timedOutStopKeepsLoaderUntilExitAndCannotRepublishOrReload`、`stoppedLoaderIsCollectableWhileStopPoolRemainsAlive` |
| JDK 21 行为 | `PluginRuntime.unloaded` 显式置 null | `TestClassLoaderUnloading.testLeakedThreadKeepsClassLoaderAliveUntilItStopsAndIsReleased` |
| 启动失败清理 | `DefaultPluginLifecycleManager.start` catch 块 | `TestDefaultPluginLifecycleManager` |
| 资源数指标 | `DefaultResourceRegistry.register/closeAll` 中计数 | `TestLprMetricsCollector` |
