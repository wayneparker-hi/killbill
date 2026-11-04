# Capability: JAX-RS REST API

> Kill Bill 对外暴露 JAX-RS REST API，覆盖账户、订阅、目录、发票、支付、插件、管理、安全等全部核心域。

## Requirements

### Requirement: 资源覆盖完整业务域

系统 SHALL 为每个核心域提供 REST 资源：Account、Bundle、Subscription、Catalog、Invoice、Payment、PaymentMethod、Tenant、Plugin、Admin 等。

#### Scenario: 创建账户
- **WHEN** `POST /1.0/kb/accounts`
- **THEN** 返回 201 与创建的账户 JSON

### Requirement: 异常映射

所有业务异常 SHALL 映射为规范的 HTTP 响应，包含错误码、消息与可读描述。

#### Scenario: 账户不存在
- **WHEN** 查询不存在的账户
- **THEN** 返回 404 与 `BillingExceptionJson`

### Requirement: 插件 HTTP Servlet 路由

插件注册的 `jakarta.servlet.Servlet` SHALL 可通过 `/plugins/<pluginId>/...` 访问。

#### Scenario: 插件暴露 REST 端点
- **WHEN** 插件注册一个 Servlet
- **THEN** 请求 `/plugins/<pluginId>/xxx` 被路由到该 Servlet

### Requirement: 节点命令接口

系统 SHALL 暴露节点命令端点，用于集群内安装/启动/停止插件、触发垃圾回收等。

#### Scenario: 安装插件
- **WHEN** `POST /1.0/kb/nodesInfo` 带 `INSTALL_PLUGIN` 命令
- **THEN** 各节点执行安装/激活

## MODIFIED Requirements

无。

## 实现与验证对照

| Requirement | 实现（文件:行 / 组件） | 验证（测试类） |
|---|---|---|
| 资源覆盖 | `AccountResource`、`InvoiceResource`、`PaymentResource` 等 | `Test*Resource` |
| 异常映射 | `BillingExceptionBaseMapper` 及各领域 mapper | `TestExceptionMapper` |
| 插件 Servlet 路由 | `PluginServletRouter` / `PluginServlet` | `TestPluginServletRouter`、`TestPluginLifecycleAndHttpEndToEnd` |
| 节点命令 | `NodeInfoResource` / `PluginNodeCommandListener` | 节点命令集成测试 |
