# Capability: Payments

> 支付层负责把抽象支付操作路由到具体网关插件，管理支付方法、支付尝试、交易状态与重试策略。

## Requirements

### Requirement: 支付方法管理

系统 SHALL 支持为账户添加、删除、设置默认支付方法；支付方法通常由插件生成 token。

#### Scenario: 添加信用卡
- **WHEN** 调用 `PaymentApi.addPaymentMethod` 并指定插件名称
- **THEN** 插件创建 token，系统持久化支付方法

### Requirement: 交易类型

系统 SHALL 支持 AUTHORIZE、CAPTURE、PURCHASE、REFUND、CREDIT、VOID、CHARGEBACK 等交易类型。

#### Scenario: 执行购买
- **WHEN** 调用 `createPurchase`
- **THEN** 系统创建 payment、payment attempt、transaction，调用插件完成扣款

### Requirement: 支付插件路由

系统 SHALL 通过 `PaymentPluginApi` 注册表按 plugin name / selector 找到对应网关，不得硬编码具体插件。

#### Scenario: Stripe 支付
- **WHEN** 支付方法指定 Stripe gateway
- **THEN** Core 查询 registry 获得 Stripe 插件实现并调用

### Requirement: 支付控制插件

系统 SHALL 支持 `PaymentControlPluginApi` 介入支付路由、重试、自动退款与状态机控制。

#### Scenario: 重试策略
- **IF** 控制插件声明某失败应重试
- **THEN** 系统按插件策略调度后续 payment attempt

### Requirement: 交易状态一致性

支付操作 SHALL 保证 transaction 状态与插件返回一致；插件异常或超时时系统进入 PENDING 并支持后续修复。

#### Scenario: 网关超时
- **IF** 插件调用超时
- **THEN** transaction 标记为 PENDING，管理员可查询并手工修复

### Requirement: 支付插件调用保护

所有 `PaymentPluginApi` / `PaymentControlPluginApi` 调用 SHALL 受平台统一超时与熔断保护；默认超时 30s，支持按 pluginId 覆盖，连续失败 N 次后熔断并抛明确异常。

#### Scenario: 支付网关死循环
- **IF** 某支付插件方法死循环或阻塞
- **THEN** 平台在配置超时内返回保护异常并请求中断；未退出任务仍占并发额度，Core 请求线程不被无限阻塞

#### Scenario: 连续失败熔断
- **IF** 某插件连续失败达到阈值
- **THEN** 后续调用快速失败，返回 `PluginInvocationException`，避免无效等待

### Requirement: 保护层保留业务异常与有限并发

保护层 SHALL 原样透传插件声明的受检异常；仅平台保护失败使用 PluginInvocationException。调用线程与每插件并发必须有界，旧代请求结果不得改变新代熔断状态。

#### Scenario: 插件忽略中断
- **WHEN** 超时任务仍未退出
- **THEN** 该任务继续占用插件并发额度，新请求在额度耗尽时快速失败，其他插件仍可调用

## MODIFIED Requirements

无。

## 实现与验证对照

| Requirement | 实现（文件:行 / 组件） | 验证（测试类） |
|---|---|---|
| 支付方法 | `DefaultPaymentApi.addPaymentMethod` | `TestPaymentMethod` |
| 交易类型 | `PaymentSMHelper` / `PaymentAutomatonRunner` | `TestPaymentApi` |
| 插件路由 | `PluginRoutingPaymentProviderPlugin` / registry | `TestPluginDispatcher` |
| 支付控制 | `PaymentControlPluginApi` / `RetryService` | `TestControlPluginRunner` |
| 状态一致性 | `PaymentDao` / Janitor | `TestPaymentWithControl` |
| 调用保护 | `PluginServiceCircuitBreakerProxy` / `PluginInvocationGuard` | `TestPluginInvocationGuard`, `TestPluginServiceCircuitBreakerProxy` |
| 受检异常透传、有界并发、熔断代次 | `PluginInvocationGuard` / `CircuitBreaker` | `TestInvocationIsolation` |
