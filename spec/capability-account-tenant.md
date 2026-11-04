# Capability: Account & Tenant Management

> 账户是 Kill Bill 计费对象的根实体；租户是多租户部署下的隔离边界。所有业务数据访问必须带租户上下文。

## Requirements

### Requirement: 账户 CRUD 与按 ID/External Key 查询

系统 SHALL 支持创建、读取、更新账户，并允许通过 `accountId` 或 `externalKey` 查询。

#### Scenario: 创建账户
- **WHEN** 调用 `AccountUserApi.createAccount` 传入账户数据
- **THEN** 账户被持久化，返回带 `accountId` 的 `Account` 对象

#### Scenario: 用 external key 查询
- **WHEN** 提供商户侧 `externalKey`
- **THEN** 系统返回对应账户，不存在时抛 `AccountApiException`

### Requirement: 租户隔离

不同租户的数据 SHALL 在 API、DAO、审计日志层面隔离；一个请求 MUST 携带 `tenantId` / `apiKey` 才能访问数据。

#### Scenario: 跨租户访问
- **WHEN** 租户 A 的用户尝试查询租户 B 的账户
- **THEN** 认证/授权层拒绝访问

### Requirement: 自定义字段与标签

账户、发票、订阅等实体 SHALL 支持自定义字段（Custom Field）和标签（Tag），允许商户扩展元数据。

#### Scenario: 给账户打标签
- **WHEN** 为账户添加 `TagDefinition` 定义的标签
- **THEN** 标签被持久化，可在查询时返回

### Requirement: 审计日志

实体的创建、更新、删除 SHALL 产生审计日志（Audit Log），记录操作人、时间、变更前后值。

#### Scenario: 查询账户审计
- **WHEN** 调用带 `AuditLevel` 的查询接口
- **THEN** 返回对应实体的审计历史

## MODIFIED Requirements

无。

## 实现与验证对照

| Requirement | 实现（文件:行 / 组件） | 验证（测试类） |
|---|---|---|
| 账户 CRUD | `DefaultAccountUserApi` / `AccountDao` | `TestDefaultAccountUserApi` |
| 租户隔离 | `TenantFilter` / `KillbillJdbcTenantRealm` / `CallContext` | `TestTenantFilter`、`TestKillbillJdbcTenantRealm` |
| 自定义字段 | `CustomFieldUserApi` / `TagUserApi` | `TestCustomField` / `TestTag` |
| 审计日志 | `EntitySqlDao` 审计 Mixin | 各模块审计测试 |
