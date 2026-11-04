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

### Requirement: 可选进程外插件研究适配器

系统 MAY 提供显式使用的 `lpr-process` PoC：插件实现 JDK-only `ProcessPlugin` 契约，在独立 JVM 执行；调用经本机 Unix socket 传输有界 UTF-8 数据列表。此能力不接入默认 Guice/节点命令，不承诺完整 PaymentPluginApi 远程化。
子进程 MUST 仅加载运行时所需 classpath，插件 JAR 与私有依赖通过现有隔离 ClassLoader 加载；不得复制整个宿主 classpath，不得使用 Java 对象反序列化。

#### Scenario: 支付授权跨进程
- **WHEN** PoC 插件通过现有 ServiceRegistry/支付 typed registry 提供 authorizePayment
- **THEN** 执行 PID 与 Core 不同，账户/支付/交易/支付方式/金额/货币/租户身份可往返，Core 收到自身加载的 PaymentTransactionInfoPlugin

### Requirement: 有界进程调用与失败隔离

每个实例 SHALL 只允许一个在途调用，超额立即拒绝；启动、读写及关闭 MUST 有单调时钟截止时间。协议 MUST 验证版本、关联 ID、长度与响应状态。
崩溃、截断、超时、取消或非法响应 SHALL 使通道失效并终止子进程；不得把失败转换成支付成功或自动重试。调用一旦可能发送，失败 MUST 明确表示执行结果未知。

#### Scenario: 子进程崩溃与假死
- **WHEN** 插件 Runtime.halt、超时、忽略线程中断或启动不返回
- **THEN** Core JVM 与其他插件仍可调用，子进程在关闭预算内被销毁，不增长调用线程或重试支付

#### Scenario: 协议与准入拒绝
- **WHEN** 消息超出预算、响应关联 ID 错误、协议版本不兼容、响应截断或已有调用执行
- **THEN** 显式失败；无效响应不返回业务成功，准入拒绝不会触发额外调用

### Requirement: 进程资源归属与清理

每个实例 SHALL 独占权限 0700 的临时目录与 socket；父进程关闭通道时子进程退出。client MUST 纳入 ResourceRegistry；停止时先撤销服务，再由既有生命周期关闭 client、终止进程、关闭连接及删除 socket/临时目录；重复关闭安全。初始化失败也执行同等清理。
进程隔离是可信插件的故障边界，不是 OS 沙箱；不承诺抵御同 UID 恶意程序、子孙进程或宿主整机资源耗尽。

#### Scenario: 生命周期与启动失败
- **WHEN** 插件停止、入口加载失败、启动失败或资源关闭
- **THEN** 无残留子进程/socket；停止旧版不会撤销新版服务，父 ClassLoader 可卸载

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
| 进程外授权 PoC / 业务数据校验 | `lpr-process`、研究代理 `ProcessAuthorizationPoc`（test source） | `TestProcessPaymentAuthorization` |
| 有界帧 / 协议 / 关联 ID | `RpcFrame` / `UnixRpcChannel` / `ProcessPluginClient` | `TestRpcProtocol`、`TestUnixRpcChannel` |
| 崩溃 / 真实子 JVM OOM / 假死 / 截止时间 | 独立 worker + 有界终止 | `TestProcessPluginClient` |
| 进程资源 / 版本作用域 / loader GC | ResourceRegistry + launcher/client | `TestProcessPaymentAuthorization`、`TestProcessPluginClient.testStartupFailureAndDeadlineCleanEveryResource` |
| 必要 classpath / JVM 环境隔离 | `ProcessPluginLauncher.runtimeClasspath` / 环境清理 | `TestProcessPluginClient.testRealForkPrivateClassAndDataRoundTrip`、`testInheritedJavaOptionsCannotOverrideWorkerRuntime` |
