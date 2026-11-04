# Capability: Invoicing

> 发票是 Kill Bill 的核心输出：按订阅周期、用量、价格覆盖生成账单项，并支持与支付关联、贷项、调整、部分支付。

## Requirements

### Requirement: 周期性发票生成

系统 SHALL 按订阅的 billing period 自动生成发票；新订阅创建、计划变更、续期都会触发发票生成。

#### Scenario: 月度订阅续期
- **WHEN** 到达下一个 billing day
- **THEN** 系统生成新的发票，包含对应周期的 RECURRING 项

### Requirement: 用量项计费

发票 SHALL 能包含基于用量的 USAGE 项，按目录中定义的 tiered/volume/staircase 价格计算。

#### Scenario: 用量发票
- **WHEN** invoicing 周期内存在 usage record
- **THEN** 发票包含按价格模型计算的 USAGE item

### Requirement: 贷项与调整

系统 SHALL 支持对发票添加贷项（Credit）、调整（Adjustment）与外部费用（External Charge）。

#### Scenario: 给账户增加贷项
- **WHEN** 调用 `InvoiceUserApi.insertCredit`
- **THEN** 生成贷项发票项，可用于抵扣后续发票

### Requirement: 发票与支付关联

系统 SHALL 跟踪每张发票的已支付金额、未付余额，并支持部分支付。

#### Scenario: 部分支付
- **WHEN** 支付金额小于发票金额
- **THEN** 发票状态为 PARTIAL，余额继续待收

### Requirement: 发票插件扩展

系统 SHALL 允许通过 `InvoicePluginApi` 在发票生成前后插入自定义逻辑或调整项。

#### Scenario: 自定义税费
- **WHEN** 插件实现 `InvoicePluginApi` 并返回额外 invoice items
- **THEN** 最终发票包含插件插入的税费项

## MODIFIED Requirements

无。

## 实现与验证对照

| Requirement | 实现（文件:行 / 组件） | 验证（测试类） |
|---|---|---|
| 周期性发票 | `DefaultInvoiceGenerator` / `InvoiceDispatcher` | `TestInvoiceGenerator` |
| 用量计费 | `UsageInvoiceItemGenerator` | `TestUsageInvoicing` |
| 贷项调整 | `InvoiceUserApi` credit/adjustment 方法 | `TestInvoiceAdjustment` |
| 支付关联 | `InvoicePayment` / `InvoiceDao` | `TestInvoicePayment` |
| 发票插件 | `InvoicePluginApi` / `InvoicePluginDispatcher` | `TestInvoicePlugin` |
