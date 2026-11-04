# Capability: Overdue & Dunning

> 逾期管理根据未付发票与配置的逾期状态机，自动把账户迁移到不同逾期状态，并触发对应动作（通知、暂停权益、取消订阅）。

## Requirements

### Requirement: 逾期状态机配置

系统 SHALL 支持通过 XML/JSON 配置逾期状态机，包括每个状态的条件、进入动作、退出动作、后续状态。

#### Scenario: 上传逾期配置
- **WHEN** 上传 overdue config
- **THEN** 系统按配置解析状态机，后续发票到期时按规则评估

### Requirement: 自动状态迁移

系统 SHALL 在发票到期未付后，按时间条件自动把账户迁移到更高逾期状态。

#### Scenario: 30 天未付
- **WHEN** 发票逾期 30 天
- **THEN** 账户进入对应逾期状态，可能暂停权益

### Requirement: 状态进入/退出动作

每个逾期状态 SHALL 可配置动作，如发送通知、暂停 entitlement、取消订阅。

#### Scenario: 进入严重逾期状态
- **WHEN** 账户进入 CANCELLATION 状态
- **THEN** 系统执行配置动作（如取消订阅）

### Requirement: 恢复路径

当未付余额被清偿后，系统 SHALL 支持把账户恢复至正常状态。

#### Scenario: 支付逾期发票
- **WHEN** 逾期发票被全额支付
- **THEN** 账户移出逾期状态，权益恢复

## MODIFIED Requirements

无。

## 实现与验证对照

| Requirement | 实现（文件:行 / 组件） | 验证（测试类） |
|---|---|---|
| 状态机配置 | `DefaultOverdueConfig` / `OverdueConfig` | `TestOverdueConfig` |
| 自动迁移 | `DefaultOverdueCheckPoster` / `OverdueAsyncBusNotifier` | `TestOverdueIntegration` |
| 进入退出动作 | `OverdueState` actions | `TestOverdueActions` |
| 恢复路径 | `OverdueState.clearState` | `TestOverduePayment` |
