# Capability: In-Process Plugin Event Bus

> Kill Bill 的插件运行时能力：为插件提供进程内 publish/subscribe， delivery 在发布线程同步完成；handler 异常不得影响其他 handler 或 core 操作。当前由 LPR 实现。

## Requirements

### Requirement: 插件可订阅事件

插件 SHALL 能通过 `PluginContext.eventBus().subscribe(Class<T>, EventHandler<T>)` 订阅事件。

#### Scenario: 订阅 STARTED 事件
- **WHEN** 插件在 `start()` 中订阅 `LifecycleEvent.STARTED`
- **THEN** Core 发布该事件时，插件 handler 被调用

### Requirement: 插件可发布事件

插件 SHALL 能通过 `EventBus.publish(Object)` 发布事件，同 JVM 内的订阅者收到。

#### Scenario: 插件发布自定义事件
- **WHEN** 插件发布一个业务事件
- **THEN** 同 JVM 内订阅了该事件类型的 handler 被调用

### Requirement: Handler 异常隔离

单个 handler 抛异常 SHALL NOT 阻止其他 handler 收到事件，也 SHALL NOT 让发布事件的核心操作失败。

#### Scenario: 一个 handler 抛异常
- **IF** 多个插件订阅了同一事件，其中一个 handler 抛异常
- **THEN** 其他 handler 正常收到，core 操作继续完成

### Requirement: 订阅/取消订阅并发安全

并发 subscribe 与 unsubscribe SHALL NOT 导致订阅静默丢失或重复。

#### Scenario: 并发订阅取消
- **WHEN** 多个线程同时 subscribe 和 unsubscribe 同一事件类型
- **THEN** 最终集合与预期一致，无丢失、无幽灵订阅

### Requirement: Handler 在自身 ClassLoader 下执行

事件分发 SHALL 把线程上下文 ClassLoader 切换为 handler 所在插件的 ClassLoader。

#### Scenario: handler 使用 ServiceLoader
- **WHEN** handler 内部使用 `ServiceLoader` 或反射库
- **THEN** 它能看到插件自己的类，而不是发布者的 ClassLoader

## MODIFIED Requirements

无。

## 实现与验证对照

| Requirement | 实现（文件:行 / 组件） | 验证（测试类） |
|---|---|---|
| 订阅/发布 | `DefaultEventBus.subscribe` / `publish` | `TestDefaultEventBus` |
| Handler 异常隔离 | `DefaultSubscription.deliver` catch 块 | `TestDefaultEventBus` |
| 并发安全 | `DefaultEventBus.subscribe` / `unsubscribe` 的 `compute` | `TestDefaultEventBus.testConcurrentSubscribeAndUnsubscribeNeverLosesASubscription` |
| TCCL 切换 | `DefaultSubscription.deliver` 中 setContextClassLoader | `TestDefaultEventBus` |
| 发布/失败计数 | `DefaultEventBus.publish` / `DefaultSubscription.deliver` catch 块 | `TestLprMetricsCollector` |
