# Capability: Kill Bill Platform Bridge

> Kill Bill 的平台集成能力：把插件运行时的能力桥接到 Kill Bill 的 Guice 装配、生命周期、节点命令、HTTP 路由和 13 个业务扩展点。当前实现为 `killbill-platform/plugin-runtime`。

## Requirements

### Requirement: 插件运行时挂在 Kill Bill 生命周期上

`PluginRuntimeService` SHALL 响应 `INIT_PLUGIN`、`START_PLUGIN`、`STOP_PLUGIN`，且 `START_PLUGIN` 属 `STARTUP_PRE`。

#### Scenario: Kill Bill 启动
- **WHEN** Kill Bill 启动
- **THEN** 插件先被发现、再启动，然后 Core service 才开始启动

### Requirement: 业务扩展点自动桥接

`PluginRuntimeModule` SHALL 通过 Guice Multibinder 收集所有 `PluginServiceRegistry<?>` 与 `SinglePluginServiceRegistry<?>`，并桥接到 LPR ServiceRegistry。

#### Scenario: 新增支付插件类型
- **WHEN** 业务模块新增 `PluginServiceRegistry<PaymentPluginApi>`
- **THEN** 平台自动桥接，无需修改 `PluginRuntimeModule`

### Requirement: NotificationPluginApi 走统一 Registry

`NotificationPluginApi` SHALL 和其余 12 个扩展点一样走 `PluginServiceRegistry`，不再依赖 OSGi 时代的 observer 回调。

#### Scenario: 插件实现通知接口
- **WHEN** 插件注册 `NotificationPluginApi`
- **THEN** 外部事件经 dispatcher 路由到 registry 中的实现

### Requirement: 节点命令驱动安装/启停

`INSTALL_PLUGIN`、`START_PLUGIN`、`STOP_PLUGIN`、`RESTART_PLUGIN` 等节点命令 SHALL 被 `PluginNodeCommandListener` 接收并转给 `PluginManager`/`PluginInstaller`。

#### Scenario: 集群安装插件
- **WHEN** 通过节点命令安装插件
- **THEN** 各节点收到命令并执行安装/激活

### Requirement: 插件 HTTP Servlet 路由

插件注册的 `jakarta.servlet.Servlet` SHALL 可通过 `/plugins/<pluginId>/...` 访问。

#### Scenario: 插件暴露 REST 端点
- **WHEN** 插件注册一个 Servlet
- **THEN** 请求 `/plugins/<pluginId>/xxx` 被路由到该 Servlet

## MODIFIED Requirements

无。

## 实现与验证对照

| Requirement | 实现（文件:行 / 组件） | 验证（测试类） |
|---|---|---|
| 生命周期挂载 | `PluginRuntimeService` | 集成测试 / 启动测试 |
| 扩展点桥接 | `KillbillPluginServiceBridge` / `PluginRuntimeModule` | `TestPluginRuntimeModule` |
| Notification 统一 | `PluginEventDispatcher` + `MapBackedPluginServiceRegistry<NotificationPluginApi>` | `TestPluginRuntimeEndToEnd` |
| 节点命令 | `PluginNodeCommandListener` | 节点命令集成测试 |
| HTTP 路由 | `PluginServletRouter` / `PluginServlet` | `TestPluginServletRouter`、`TestPluginLifecycleAndHttpEndToEnd` |
