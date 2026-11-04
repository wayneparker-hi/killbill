# Capability: Notifications & Persistent Bus

> Kill Bill 的异步骨架：持久化事件总线（Persistent Bus）保证领域事件至少投递一次；通知队列（Notification Queue）用于调度未来某个时间点必须执行的任务。

## Requirements

### Requirement: 持久化事件总线

系统 SHALL 提供 `PersistentBus`，发布事件时先写数据库，再异步投递给订阅者；订阅者失败可重试。

#### Scenario: 订阅支付事件
- **WHEN** 业务模块订阅 `PaymentInfoInternalEvent`
- **THEN** 每次支付成功后事件被持久化并投递

### Requirement: 事件投递至少一次

事件 SHALL 被持久化，直到所有订阅者确认处理或达到重试上限；系统重启后不丢失。

#### Scenario: 系统重启
- **WHEN** 系统重启时数据库中有未投递事件
- **THEN** 启动后重新投递这些事件

### Requirement: 通知队列调度未来任务

系统 SHALL 支持在指定未来时间触发通知，用于发票提醒、逾期检查、续期等。

#### Scenario: 下一张发票提醒
- **WHEN** 创建订阅时调度 next billing date 通知
- **THEN** 到达日期时通知队列触发 invoicing 流程

### Requirement: handler 异常隔离

单个 handler 抛异常 SHALL NOT 阻塞其他 handler 收到同一事件。

#### Scenario: 一个 handler 失败
- **IF** 多个订阅者中一个抛异常
- **THEN** 其他订阅者仍正常处理，失败事件进入重试/死信

### Requirement: 可确认的持久化入口

`PersistentBus.postReliable` 和 `registerReliable` SHALL 为可选严格入口，默认拒绝不支持的实现；数据库实现仅在初始化且非内存模式接受。写入失败 MUST 向调用方传播；旧 `post` 的兼容行为保留。

#### Scenario: 严格写入失败
- **WHEN** 使用未初始化、内存或写库失败的总线
- **THEN** 严格入口抛出 EventBusException

### Requirement: 插件事件失败历史

持久化插件信封 SHALL 复用 PersistentBus 的重试及 `*_history.processing_state=FAILED`；运维按信封 class_name 与 UUID 查询死信。历史查询 API 不含 processing_state，不得用它判断死信。

#### Scenario: 失败持久化
- **WHEN** 插件投递持续失败超过重试次数
- **THEN** 历史表中有相同 UUID 的 FAILED 记录

### Requirement: 共享通知队列停止结果与完成排空

通知队列 SHALL 区分逻辑队列停用与共享调度器关闭。非最后一个逻辑队列停用成功 MUST 返回 true；最后一个队列停止时按 reaper → 获取线程 → handler → 完成线程关闭，实际超时 MUST 返回 false。完成线程退出前 SHALL 排空已经入队的完成与重试记录。

#### Scenario: 多队列依次停止
- **WHEN** 两个逻辑队列共享调度器，依次停止
- **THEN** 第一次停止成功且其他队列仍可处理通知；最后一次等待已开始的 handler 与完成写库，重复停止安全

#### Scenario: 完成批次超过单次处理上限
- **WHEN** 停止完成线程时仍有多个批次的完成或重试记录
- **THEN** 所有已入队记录均写库后线程退出，不能仅因停止标记丢下剩余批次

#### Scenario: 真实超时与恢复
- **WHEN** handler 超过关闭预算，或持久化的待处理通知在重启时仍未完成
- **THEN** 超时停止返回 false；同库重新注册队列可处理待处理通知，成功完成的通知不因正常重启而重复执行；崩溃恢复仍遵循至少一次语义

## MODIFIED Requirements

无。

## 实现与验证对照

| Requirement | 实现（文件:行 / 组件） | 验证（测试类） |
|---|---|---|
| 持久化总线 | `DefaultPersistentBus` / `EventEntry` DAO | `TestPersistentBus` |
| 至少一次 | `InMemoryPersistentBus` 测试 / DB 表 `bus_events` | `TestEventBase` |
| 通知队列 | `DefaultNotificationQueue` / `NotificationEvent` | `TestNotificationQueue` |
| handler 隔离 | `DefaultPersistentBus.deliverEvent` catch 块 | `TestBus` |
| 严格写入 / 注册 / 内存拒绝 | `PersistentBus.postReliable` / `registerReliable` | `TestPersistentPluginEventStorage.testStrictEntryRejectsUninitializedMemorySerializationAndStorageFailures` |
| 插件持久化恢复 / 重试 / 死信 | `PersistentPluginEventBus`、队列历史表 | `TestPersistentPluginEventStorage`（隔离 H2 文件数据库 + 生产 DDL/DAO） |
| 停机不静默 ACK | 保留拒绝监听器至总线停止 | `TestPersistentPluginEventStorage.testStoppedAdapterDoesNotSilentlyAcknowledgeQueueDrain` |
| 共享队列停止 / 超时 / 重启 | `NotificationQueueDispatcher.stopQueue` | `TestNotificationQueueShutdown` |
| 完成与重试排空 | `DefaultQueueLifecycle.CompletionRunnable` | `TestQueueCompletionShutdown` |
