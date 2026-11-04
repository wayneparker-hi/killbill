# Capability: Plugin Service Registry

> Kill Bill 的插件扩展能力：插件注册实现，Core 按类型与 selector 查找，业务层只表达需求而不指定具体插件。当前由 LPR 实现。

## Requirements

### Requirement: 注册按 (pluginId, version) 作用域

服务注册与撤销 SHALL 以 `(pluginId, version)` 为作用域，不能只按 `pluginId`。

#### Scenario: 无空窗升级期间撤销旧版本
- **WHEN** 新旧两个版本共享同一个 pluginId 且同时注册
- **THEN** 撤销旧版本 MUST NOT 影响新版本的服务

### Requirement: 插件只能以自己身份注册

通过 `ServiceRegistry.forPlugin(pluginId, version)` 得到的视图 SHALL 把 pluginId/version 打到每个注册上，插件无法假冒其他插件。

#### Scenario: 插件 A 尝试注册为插件 B
- **WHEN** 插件 A 拿到自己的 registry 视图
- **THEN** 它所有注册都携带 A 的 pluginId，无法以 B 名义发布

### Requirement: 已停止插件不可达

`STOPPING` 期间 SHALL 先撤销该版本所有注册，确保新调用者不会拿到即将关闭 ClassLoader 的实现。

#### Scenario: 停止过程中新请求到达
- **WHEN** 插件正在停止
- **THEN** `getService` / `getServices` 不再返回该版本实现

### Requirement: 服务查询稳定排序

同类型的多个 provider SHALL 按 priority 降序、pluginId 升序返回，保证重启后顺序一致。

#### Scenario: 多个插件实现同一接口
- **WHEN** 查询某类型的所有服务
- **THEN** 返回顺序按 priority 高到低，同 priority 按 pluginId 字母序

### Requirement: Core 不得依赖具体插件

Core 代码 SHALL NOT 出现 `instanceof XxxPlugin` 或指向具体插件的 pom 依赖。

#### Scenario: 调用支付插件
- **WHEN** Core 处理支付
- **THEN** 它通过 capability + selector 查询 `PaymentPluginApi`，而不是直接引用 Stripe 类

### Requirement: 注册服务调用受平台保护

桥接到 Kill Bill 业务注册表的服务实现 SHALL 被 `PluginServiceCircuitBreakerProxy` 包装，使每次调用都经过超时与熔断。

#### Scenario: 调用已注册插件服务
- **WHEN** Core 从 registry 取出插件服务并调用
- **THEN** 调用先经过熔断检查，再提交到独立 executor 执行并受超时保护

### Requirement: 健康状态覆盖平台业务路由

健康状态与版本切换 SHALL 同时影响 LPR lookup 及桥接注册表；健康检查端点仍须能报告不健康状态。

#### Scenario: 插件不健康与恢复
- **WHEN** 当前版本健康检查失败，随后恢复
- **THEN** 平台普通、单值与支付等注册表撤回不健康版本并在恢复后重新提供；存在保留旧版时优先回退到健康旧版

## MODIFIED Requirements

无。

## 实现与验证对照

| Requirement | 实现（文件:行 / 组件） | 验证（测试类） |
|---|---|---|
| (pluginId, version) 作用域 | `DefaultServiceRegistry.unregisterAll(String,String)` | `TestPluginRuntimeEndToEnd` |
| 插件身份绑定 | `PluginScopedServiceRegistry` | `TestDefaultServiceRegistry` |
| 停止即不可达 | `DefaultPluginLifecycleManager.stop` 中先 unregister | `TestDefaultPluginLifecycleManager` |
| 稳定排序 | `DefaultServiceRegistry.getServices` 排序逻辑 | `TestDefaultServiceRegistry` |
| Core 不依赖具体插件 | `killbill/` 与 `killbill-platform/` 依赖关系 | 依赖分析 + 代码审查 |
| Provider 数指标 | `DefaultServiceRegistry` 中调用 `LprMetricsCollector.providerRegistered/Unregistered` | `TestLprMetricsCollector` |
| 调用保护 | `KillbillPluginServiceBridge` 中 `PluginServiceCircuitBreakerProxy.wrap` | `TestPluginServiceCircuitBreakerProxy` |
| 健康状态影响可见性 | `DefaultServiceRegistry.setHealthPredicate` 过滤 unhealthy 版本 | `TestDefaultServiceRegistryHealthFiltering` |
| 健康检查桥接 | `KillbillPluginServiceBridge` 将 `PluginHealthCheck` 路由到 `PluginServiceRegistry<Healthcheck>` | `TestKillbillPluginServiceBridge#testHealthCheckIsRoutedToTheHostRegistry` |
| 宿主路由健康过滤、恢复与回退 | `DefaultServiceRegistry.refreshVisibility/isVisible` / `KillbillPluginServiceBridge` | `TestKillbillPluginServiceBridge.testHostRoutesFollowHealthFallbackAndRecovery`、`testSingleRegistryFallsBackAcrossDifferentNames` |
