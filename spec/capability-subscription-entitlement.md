# Capability: Subscription & Entitlement

> 订阅是用户与计划之间的契约；权益（Entitlement）管理用户何时能使用服务。订阅生命周期驱动计费事件。

## Requirements

### Requirement: 订阅生命周期

系统 SHALL 支持创建、变更计划（change plan）、取消、暂停（pause）、恢复（resume）订阅。

#### Scenario: 创建订阅
- **WHEN** 调用 `SubscriptionApi.createSubscription`
- **THEN** 创建 Subscription 与 Bundle，权益进入 ACTIVE 状态

#### Scenario: 变更计划
- **WHEN** 调用 change plan
- **THEN** 按目录规则计算变更策略（START_OF_BILLING_PERIOD / IMMEDIATE 等），生成发票调整

### Requirement: 权益与计费解耦

权益状态（如 ACTIVE、BLOCKED）与计费周期 SHALL 可独立控制，支持先暂停服务再停止计费。

#### Scenario: 暂停权益
- **WHEN** 调用 `EntitlementApi.pause`
- **THEN** 权益进入 BLOCKED，后续发票暂停生成（取决于 billing policy）

### Requirement: 包（Bundle）与订阅树

一个 Bundle 内 SHALL 可包含多个 Subscription（基础计划 + 附加组件），并支持按 Bundle 查询。

#### Scenario: 添加附加组件
- **WHEN** 在已有 Bundle 下创建 addon 订阅
- **THEN** addon 与 base subscription 关联，发票合并展示

### Requirement: 审计与修复

系统 SHALL 记录订阅状态变更事件，并支持管理员按时间线修复不一致状态。

#### Scenario: 查看订阅时间线
- **WHEN** 查询 bundle timeline
- **THEN** 返回按时间排序的 blocking states 与 subscription events

## MODIFIED Requirements

无。

## 实现与验证对照

| Requirement | 实现（文件:行 / 组件） | 验证（测试类） |
|---|---|---|
| 订阅生命周期 | `DefaultSubscriptionApi` / `SubscriptionBase` | `TestSubscription` |
| 权益控制 | `DefaultEntitlementApi` / `BlockingState` | `TestEntitlement` |
| Bundle 模型 | `SubscriptionBaseBundle` / `BundleTimeline` | `TestBundle` |
| 审计与修复 | `SubscriptionBaseTimeline` / repair API | `TestRepair` |
