# Capability: Security, Authentication & Authorization

> Kill Bill 通过 Apache Shiro 提供认证、RBAC 授权、多租户隔离与审计日志，保护商户与租户数据。

## Requirements

### Requirement: 多因素认证 Realm

系统 SHALL 支持 JDBC realm、LDAP、Auth0、Okta 等多种认证源，并可在配置中切换。

#### Scenario: JDBC 认证
- **WHEN** 用户提供用户名密码
- **THEN** `KillBillJdbcRealm` 验证密码与角色

### Requirement: RBAC 权限控制

系统 SHALL 支持角色（Role）与权限（Permission）模型，API 资源通过注解声明所需权限。

#### Scenario: 无权限访问
- **WHEN** 无权限用户调用管理员接口
- **THEN** `PermissionAnnotationMethodInterceptor` 拒绝并返回 403

### Requirement: 租户上下文绑定

认证成功后，系统 SHALL 把租户信息绑定到请求的 `CallContext`，后续 DAO 查询带租户过滤。

#### Scenario: 带租户过滤查询
- **WHEN** 通过 `TenantFilter` 认证请求
- **THEN** `CallContext` 携带 tenantId，账户查询只返回该租户数据

### Requirement: 会话管理

系统 SHALL 支持 JDBC 或 Redis 会话存储，支持集群部署时的会话共享。

#### Scenario: 集群会话
- **WHEN** 配置 Redis session DAO
- **THEN** 用户登录会话在多个节点间共享

## MODIFIED Requirements

无。

## 实现与验证对照

| Requirement | 实现（文件:行 / 组件） | 验证（测试类） |
|---|---|---|
| 认证 Realm | `KillBillJdbcRealm` / `KillBillAuth0Realm` / `KillBillOktaRealm` | `TestKillBillJdbcRealm`、`TestKillBillAuth0Realm`、`TestKillBillOktaRealm` |
| RBAC | `PermissionAnnotationMethodInterceptor` / `AnnotationHierarchicalResolver` | `TestPermissionAnnotationMethodInterceptor` |
| 租户绑定 | `TenantFilter` / `CallContextFactory` | `TestTenantFilter` |
| 会话管理 | `JDBCSessionDao` / `RedisSessionDao` | `TestJDBCSessionDao` |
