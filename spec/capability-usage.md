# Capability: Usage Metering

> 用量计费允许商户按实际使用量收费。系统采集原始用量记录，并在 invoicing 时按 tiered/volume 等模型计费。

## Requirements

### Requirement: 用量记录写入

系统 SHALL 支持按账户、订阅、计划阶段、使用单位写入用量记录（Rolled Up Usage）。

#### Scenario: 上报用量
- **WHEN** 调用 `UsageUserApi.recordRolledUpUsage`
- **THEN** 用量记录被持久化，与订阅/计费周期关联

### Requirement: 用量查询

系统 SHALL 支持查询某个订阅在指定时间段内的汇总用量。

#### Scenario: 查询用量
- **WHEN** 调用 `getUsage` 指定 subscriptionId 与 date range
- **THEN** 返回该周期内的用量汇总

### Requirement: 用量插件扩展

系统 SHALL 允许通过 `UsagePluginApi` 自定义用量来源或计费逻辑。

#### Scenario: 自定义用量插件
- **WHEN** 插件实现 `UsagePluginApi` 并注册
- **THEN** invoicing 时可调用插件获取用量数据

## MODIFIED Requirements

无。

## 实现与验证对照

| Requirement | 实现（文件:行 / 组件） | 验证（测试类） |
|---|---|---|
| 用量记录 | `DefaultUsageUserApi` / `RolledUpUsageDao` | `TestUsage` |
| 用量查询 | `UsageUserApi.getUsage` | `TestRolledUpUsage` |
| 用量插件 | `UsagePluginApi` / registry 桥接 | `TestUsagePlugin` |
