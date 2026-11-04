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

## MODIFIED Requirements

无。

## 实现与验证对照

| Requirement | 实现（文件:行 / 组件） | 验证（测试类） |
|---|---|---|
| 持久化总线 | `DefaultPersistentBus` / `EventEntry` DAO | `TestPersistentBus` |
| 至少一次 | `InMemoryPersistentBus` 测试 / DB 表 `bus_events` | `TestEventBase` |
| 通知队列 | `DefaultNotificationQueue` / `NotificationEvent` | `TestNotificationQueue` |
| handler 隔离 | `DefaultPersistentBus.deliverEvent` catch 块 | `TestBus` |
