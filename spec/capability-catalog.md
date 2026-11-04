# Capability: Catalog & Pricing

> 商品目录定义产品、计划、阶段、价格与价格表，是订阅计费与发票生成的根基。目录支持版本化与价格覆盖。

## Requirements

### Requirement: 目录版本化

系统 SHALL 支持多个目录版本并存，新版本生效后不影响已创建订阅的原有计费逻辑。

#### Scenario: 上传新目录版本
- **WHEN** 上传新的 catalog XML/JSON
- **THEN** 新订阅使用新版目录，旧订阅仍按创建时的版本计费

### Requirement: Plan / Phase / PriceList 定义

目录 SHALL 支持产品（Product）、计划（Plan）、阶段（Phase，如 TRIAL → DISCOUNT → EVERGREEN）、价格表（PriceList）与货币（Currency）的完整定义。

#### Scenario: 创建订阅
- **WHEN** 用户订阅一个 Plan
- **THEN** 系统按 Plan Phase 与 PriceList 解析出应计费项

### Requirement: 价格覆盖

创建订阅时，系统 SHALL 允许覆盖目录中的默认价格，且覆盖信息随订阅持久化。

#### Scenario: 协商价格
- **WHEN** 创建订阅时传入 `PlanPhasePriceOverridesWithCallContext`
- **THEN** 发票按覆盖后的价格生成

### Requirement: 目录校验

上传目录时，系统 SHALL 校验其语法与业务规则（如价格必须为正、阶段必须完整），拒绝 malformed 配置。

#### Scenario: 上传无效目录
- **IF** 目录 XML 格式错误或缺少必要字段
- **THEN** 系统抛 `CatalogApiException` 或 `InvalidConfigException`，目录不生效

## MODIFIED Requirements

无。

## 实现与验证对照

| Requirement | 实现（文件:行 / 组件） | 验证（测试类） |
|---|---|---|
| 目录版本化 | `VersionedCatalog` / `CatalogUserApi` | `TestVersionedCatalog` |
| Plan/Phase/PriceList | `DefaultCatalog` / `Plan` / `PriceListSet` | `TestCatalog` |
| 价格覆盖 | `PlanPhasePriceOverride` 处理逻辑 | `TestPlanPhasePriceOverride` |
| 目录校验 | `CatalogValidation` / `CatalogVerifier` | `TestCatalogValidation` |
