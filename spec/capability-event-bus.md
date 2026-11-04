# Capability: Plugin Event Bus

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

### Requirement: 可选持久化插件事件

插件 MAY 使用 `PluginContext.durableEvents()`。宿主未启用时 MUST 显式拒绝；现有 `eventBus()` 与生命周期事件保持同步内存分发。
持久化消息 MUST 只有平台加载的固定信封及 JDK 数据（消息 UUID、topic、文本 payload），不得反序列化插件私有类型。
信封 SHALL 兼容 Kill Bill 外部队列表的非空 search_key2，使用平台中性值 0；此索引不表达业务租户授权。
发布 MUST 指定目标 pluginId 与稳定 subscriptionId；发布成功仅表示数据库已接收，消费至少一次，插件 MUST 按消息 UUID 幂等。不保证顺序或 exactly-once。

#### Scenario: 离线后重启消费
- **WHEN** 消息写库后宿主停止，在同一数据库重启并重新注册相同订阅
- **THEN** 消息可被消费，UUID 保持不变

#### Scenario: 严格发布失败
- **WHEN** 总线未初始化、使用内存实现、序列化失败或数据库写入失败
- **THEN** 持久化发布显式失败，不得返回成功

#### Scenario: PostgreSQL 发布后进程退出
- **WHEN** 严格发布已返回成功，发布进程在消费前立即退出
- **THEN** 新 JVM 使用同库和相同订阅可消费原消息，UUID/topic/payload 不变，队列记录最终为 PROCESSED

#### Scenario: PostgreSQL 消费确认前崩溃
- **WHEN** handler 已提交业务副作用，但进程在队列完成确认前退出
- **THEN** IN_PROCESSING 记录在 claim 过期后由生产 reaper 恢复，新 JVM 再次收到相同 UUID；按 UUID 去重的业务副作用只保留一次，不承诺 handler 只调用一次

#### Scenario: PostgreSQL 写入受阻
- **WHEN** 独立验收库中的目标表被另一事务锁住，发布连接达到 lock_timeout
- **THEN** 严格发布显式失败且没有消息被误接收；释放锁后可重新发布并消费

### Requirement: 有界准入与持久化失败处理

宿主 MUST 限制单节点并发发布与执行中的 handler 数量，并限制序列化信封字节数（不超过既有 2048 字段预算）。超额发布立即拒绝；执行额度不足、无可见订阅或 handler 异常 MUST 进入 PersistentBus 重试，耗尽后进入历史表 FAILED 死信。
数据库 backlog 不承诺全局容量上限；重试次数、队列并发与历史保留由 PersistentBus 配置控制。

#### Scenario: 背压
- **WHEN** 并发发布额度已满或信封超过字节预算
- **THEN** 发布失败且不产生待投递记录

#### Scenario: 重试与死信
- **WHEN** handler 首次失败随后成功，或持续失败
- **THEN** 前者重试后为 PROCESSED，后者耗尽配置重试次数后为 FAILED

### Requirement: 持久化订阅按版本选择并释放

订阅 MUST 通过插件作用域注册到 ServiceRegistry，按已有 selector/健康/升级规则只选择一个可见 handler。取消旧版本不得影响新版本。
资源关闭 MUST 撤销订阅、拒绝后续注册/发布，并清除 handler 与 ClassLoader 强引用。已开始的调用允许完成；TCCL MUST 在调用后恢复。

#### Scenario: 升级、回滚与清理
- **WHEN** 新旧版本重叠、选择切换或旧版停止
- **THEN** 每次投递只调用一个可见版本，停止版本不再接收新调用；保留已关闭句柄也不阻止 ClassLoader 回收

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
| 固定持久化信封 / 严格发布 | `DurableEventBus` / `PersistentPluginEventBus` / `PersistentPluginEvent` | `TestPersistentPluginEventBus`、`TestPersistentPluginEventStorage` |
| 发布 / 投递背压 | adapter semaphore、信封 UTF-8 字节预算 | `TestPersistentPluginEventBus.testPublicationBackpressureAndPermitRelease`、`testDeliveryBackpressureAndTcclRestoration` |
| 重启 / 重试 / FAILED | `DefaultPersistentBus.postReliable`、队列 dispatcher / 历史表 | `TestPersistentPluginEventStorage` |
| PostgreSQL 进程退出 / 确认前崩溃 / 写入失败 | 同生产 external bus 表、reaper、固定信封与 adapter | `ITPostgresqlDurableEvents`、`DurableEventsCrashWorker` |
| 版本选择 / 资源释放 / TCCL | `DurablePluginEventScope`、ServiceRegistry、ResourceRegistry | `TestPersistentPluginEventBus.testVersionSelectionRollbackAndOldCleanup`、`TestDurablePluginEventIsolation` |
| 平台装配 / 生命周期 | `PluginRuntimeModule` / `PluginRuntimeService` | `TestPluginRuntimeModule.testModuleStartsRealDurablePluginAndReleasesSubscription` |
