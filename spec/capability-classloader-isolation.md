# Capability: Plugin ClassLoader Isolation

> Kill Bill 的插件运行时能力：让插件能携带自己的依赖版本，同时保证跨边界共享的 API 类型在插件侧与 Core 是同一个 Class 对象。当前由 LPR 实现。

## Requirements

### Requirement: API 类型由 Parent ClassLoader 加载

插件 ClassLoader SHALL 对平台 API 包使用 parent-first 策略，确保 `PaymentPluginApi.class` 等类型在两侧是同一 Class 对象。

#### Scenario: 插件实现支付 API
- **WHEN** 插件注册一个 `PaymentPluginApi` 实现
- **THEN** `PaymentPluginApi.class` 由平台 ClassLoader 加载，插件侧与 Core 侧为同一 Class 对象

### Requirement: 插件依赖由 Child ClassLoader 加载

非 parent-first 包的类 SHALL 优先从插件 jar/lib 加载，允许不同插件持有同一库的不兼容版本。

#### Scenario: 两个插件使用不同 Jackson 版本
- **WHEN** 插件 A 依赖 Jackson 2.15，插件 B 依赖 Jackson 2.18
- **THEN** 两者各自加载自己的 Jackson 类，互不冲突

### Requirement: 插件实现包回退 Child-First

`org.killbill.billing.plugin.*` SHALL 走 child-first，避免插件实现类被平台侧同名类覆盖。

#### Scenario: hello-world 插件加载自身实现
- **WHEN** `HelloWorldPlugin` 被加载
- **THEN** 它由插件自己的 ClassLoader 加载，而不是平台 ClassLoader

### Requirement: 跨边界传递的第三方类型须 Provided

任何会出现在服务方法签名里或跨边界传递的第三方类型，插件 pom MUST 声明为 `provided`。

#### Scenario: metrics-core 跨边界传递
- **IF** 插件把 `com.codahale.metrics.MetricRegistry` 传入平台注册的 registry
- **THEN** `metrics-core` 必须为 `provided`，否则运行时 ClassCastException

### Requirement: 构建期 ABI 校验

插件 verify SHALL 校验跨边界方法的直接、泛型、异常类型与继承契约，拒绝非 provided 第三方类型及重复依赖类；所需 jar/classpath 文件或条目缺失必须失败。

#### Scenario: 泛型参数携带私有第三方类型
- **WHEN** 服务返回 List<第三方 DTO>，DTO 只在 runtime 依赖中
- **THEN** verify 失败并指出方法及 DTO

#### Scenario: 校验输入缺失
- **WHEN** jar、classpath 文件或文件中引用的依赖不存在
- **THEN** 校验以错误退出，不能跳过或报告通过

### Requirement: 邮件格式化契约共享

跨插件邮件格式化工厂 SHALL 使用 parent-loaded `NotificationInvoiceFormatterFactory`；邮件插件每次渲染重新选择当前工厂，允许提供方晚启动、升级及移除。

#### Scenario: 工厂与消费者隔离加载
- **WHEN** 自定义格式化插件和邮件插件使用独立 ClassLoader
- **THEN** 工厂契约 Class 对象相同，格式化实现仍归提供方加载，移除后回退默认格式化

## MODIFIED Requirements

无。

## 实现与验证对照

| Requirement | 实现（文件:行 / 组件） | 验证（测试类） |
|---|---|---|
| API 类型 parent-first | `DefaultPluginClassLoader.loadParentFirst` / `ClassLoaderPolicy` | `TestClassLoaderIsolation` |
| 插件依赖 child-first | `DefaultPluginClassLoader.loadChildFirst` | `TestClassLoaderIsolation` |
| `org.killbill.billing.plugin.*` child-first | `ClassLoaderPolicy.defaultPolicy()` | `TestHelloWorldPluginMigration` |
| 第三方类型 provided | 插件 pom `<scope>provided</scope>` + 依赖分析 | `TestClassLoaderIsolation`、依赖检查 |
| 构建期 ABI 门禁 | `AbiValidator` / `ClassFileParser` / `ServiceAbiInspector` / `GenericHierarchy`；parent POM `lpr-plugin-abi-check` verify profile | `TestAbiValidator`：直接、泛型、上界、继承、目录、shaded、重复与缺输入；全 reactor verify |
| 邮件格式化契约共享与动态选择 | `NotificationInvoiceFormatterFactory` / `TemplateRenderer` / `EmailNotificationPlugin` | `TestSharedFormatterContract`、`TestDynamicInvoiceFormatter` |
