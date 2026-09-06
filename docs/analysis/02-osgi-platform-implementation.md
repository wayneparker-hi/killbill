# 分析报告 02：killbill-platform 的 OSGi 实现摸底

> 调研对象：`killbill-platform` 的 `osgi` / `osgi-api` / `osgi-bundles` / `lifecycle` / `server` 模块
> 目的：明确"新插件运行时必须复刻哪些能力"，以及"哪些是 Felix 强绑定、哪些是 Kill Bill 自研抽象"

---

> ⚠️ **这是改造前的快照。** 报告里描述的 OSGi 代码**已被全部删除**（141 文件 / 16408 行）。
> 保留它是因为它记录了"当时是什么样、为什么这么改"的依据；不要把它当作现状来读。
> 现状见 [`../migration/osgi-to-lpr-plan.md`](../migration/osgi-to-lpr-plan.md) 的附录三、四。



## 0. 全局结论

| 事实 | 影响 |
|---|---|
| **Kill Bill 核心几乎零 OSGi 耦合**：`killbill` 全仓只有 1 个测试文件 import `org.osgi.*`（`beatrix/src/test/.../TestWithFakeKPMPlugin.java`） | Core 侧改造成本极低 |
| **`killbill-api` 中 `org.killbill.billing.osgi.api.*` 全部是纯 Java 接口**，无 `org.osgi` 依赖 | `OSGIKillbill`、`PluginInfo`、`PluginState` 这套契约可整体保留，只换实现 |
| **Felix/OSGi 规范强绑定只集中在 40 个文件**：`osgi` 10 + `osgi-api` 1 + `osgi-bundles` 29 | 这 40 个文件就是要重写/删除的全部范围 |
| **JRuby 桥接已在本版本被彻底移除** | `README.md:9` 仍写着 "including the JRuby bridge"，但 `PluginFinder` 已 `throw new UnsupportedOperationException("Non-Java plugins aren't supported anymore")` |

---

## 1. `osgi` 模块（核心运行时，31 main 文件 / 4402 行）

路径前缀：`killbill-platform/osgi/src/main/java/org/killbill/billing/osgi/`

### 1.1 框架启停：`DefaultOSGIService`

实现 `platform-api` 的 `OSGIService extends KillbillService`，是整个 OSGi 层的生命周期入口。

- `@LifecycleHandlerType(INIT_PLUGIN) initialize()`：
  1. `pruneOSGICache()` —— 递归删除 `felix.cache.rootdir`（默认 `/var/tmp/felix`），**每次启动都清空**
  2. `createAndInitFramework()` → `new Felix(felixConfig)` + `felix.init()`
  3. `framework.start()`
  4. `bundleRegistry.installBundles(framework)`
  5. `externalBus.register(osgiListener)`
- `@LifecycleHandlerType(START_PLUGIN) start()`：`bundleRegistry.startBundles(mandatoryPlugins)`，然后广播 OSGi Event topic `org/killbill/billing/osgi/lifecycle/STARTED`
- `@LifecycleHandlerType(STOP_PLUGIN) stop()`：反注册总线 → `framework.stop()` + `waitForStop(0)` → `bundleRegistry.stopBundles()` → 广播 `.../lifecycle/STOPPED`

Felix 配置（硬编码）：
```java
config.put("org.osgi.framework.system.packages.extra", systemExtraPackages);  // api + java + extra 三段拼接
config.put("felix.cache.rootdir", osgiConfig.getOSGIBundleRootDir());
config.put("org.osgi.framework.storage", osgiConfig.getOSGIBundleCacheName());
config.put("org.osgi.framework.bundle.parent", "ext");   // 让 bundle 在 JDK11+ 能加载 java.sql.*
felixConfig.put(FelixConstants.SYSTEMBUNDLE_ACTIVATORS_PROP, List.of(killbillActivator));
```

> **注意：没有配置 `org.osgi.framework.bootdelegation`**。Kill Bill 走的是"system packages extra 显式导出"路线，不是 bootdelegation 路线。

### 1.2 Bundle 安装

**`FileInstall`**（类注释还留着 `// TODO Pierre Should we leverage org.apache.felix.fileinstall.internal.FileInstall?`）：
- `installAllOSGIBundles()` —— 从 `PureOSGIBundleFinder.getLatestBundles()` 取 `<root>/platform/` 下所有文件，`context.installBundle("file:" + path)`
- `installAllJavaPluginBundles()` —— 从 `PluginFinder.getLatestJavaPlugins()` 取 `PluginJavaConfig`，安装后 `DefaultPluginConfigServiceApi.registerBundle(bundleId, javaConfig)` 建立 **bundleId → PluginJavaConfig** 映射
- `installNewBundle(pluginName, version, framework)` —— 运行期热装
- `startBundle(Bundle)` —— 跳过 `UNINSTALLED` 和 fragment（`bundle.adapt(BundleRevision.class)` 判 `TYPE_FRAGMENT`）
- `installBundle()` 的 `switch(pluginLanguage)` 只剩 `case JAVA`

**`PureOSGIBundleFinder`** —— 扫 `<root>/platform/`，把该目录下**所有文件**（不递归）当成 pure OSGi bundle jar。

**`BundleRegistry`** —— Kill Bill 自己的插件注册表（不是 OSGi 概念）：
- `Map<String pluginName, BundleWithMetadata> registry`
- `installAndStartNewBundle` / `stopAndUninstallNewBundle`（供 `OSGIListener` 的节点命令调用）
- `checkIfMandatoryPluginsAreStarted()` —— 强制插件没起来就抛异常（`org.killbill.billing.plugin.mandatory.plugins`）
- `getPluginName(bundle)` 解析优先级：`config.getPluginName()` → `bundle.getSymbolicName()`

### 1.3 `KillbillActivator` —— 最关键的一个类

`implements BundleActivator, AllServiceListener`，作为 **Felix System Bundle 的 Activator** 运行。双重职责：

**A. 把 Core 能力发布给插件**（`start(BundleContext)`）：
```java
props.put(OSGIPluginProperties.PLUGIN_NAME_PROP, "killbill");
registrar.registerService(context, OSGIKillbill.class,        osgiKillbill,        props);
registrar.registerService(context, Observable.class,          observable,          props);  // 事件分发
registrar.registerService(context, DataSource.class,          dataSource,          props);
registrar.registerService(context, OSGIConfigProperties.class, configProperties,   props);
registrar.registerService(context, Clock.class,               clock,               props);
registrar.registerService(context, MetricRegistry.class,      metricsRegistry,     props);
registrar.registerService(context, HealthCheckRegistry.class, healthCheckRegistry, props);
```
另外把 logback root logger 通过 `OSGIAppender` 桥到 OSGi `LogService`；`jndiManager.export("killbill/osgi/jdbc", dataSource)`。

**B. 监听插件注册的服务并转发给 Core**（`serviceChanged(ServiceEvent)`）：
- `allRegistrationHandlers`：一组 `OSGIServiceRegistrable`，通过 **11 个 `@Inject` setter**（Guice method injection，全部 `@Nullable`）注入：
  `Servlet`, `PaymentPluginApi`, `InvoicePluginApi`, `CurrencyPluginApi`, `InvoiceFormatterFactory`, `PaymentControlPluginApi`, `CatalogPluginApi`, `EntitlementPluginApi`, `UsagePluginApi`, `Healthcheck`, `ServiceDiscoveryRegistry`, `OSGISingleServiceRegistration<MetricRegistry>`
- `listenForServiceType()` 逻辑：
  1. 读 `serviceReference.getProperty("killbill.pluginName")`，没有就忽略
  2. `checkSanityPluginRegistrationName()` —— 必须匹配 `\p{Lower}(?:\p{Lower}|\d|-|_)*` 且长度 ≤ 40
  3. `claz.isAssignableFrom(serviceObject.getClass())` 类型匹配（用父类判断以便 `HttpServlet` 能匹配 `Servlet`）
  4. **REGISTERED** → `ContextClassLoaderHelper` 包代理 → `registration.registerService(desc, wrappedService)` + `bundleRegistry.registerService(...)`
  5. **UNREGISTERING** → 反向操作
- `sendEvent(topic, props)` → `observable.setChangedAndNotifyObservers(new Event(topic, props))`

### 1.4 `ContextClassLoaderHelper` —— 唯一的 TCCL 处理点

**新运行时必须等价复刻。** 零 `org.osgi` 依赖，可原样保留。

- `getWrappedServiceWithCorrectContextClassLoader(service, serviceType, serviceName, metricRegistry)`：
  收集 `getAllInterfaces(serviceClass)` → `Proxy.newProxyInstance(serviceClass.getClassLoader(), interfaces, new ClassLoaderInvocationHandler(...))`
- `ClassLoaderInvocationHandler.handleInvocation()`：
  ```java
  ClassLoader initial = Thread.currentThread().getContextClassLoader();
  try { Thread.currentThread().setContextClassLoader(serviceClass.getClassLoader());
        ... Profiling(PLUGIN, "<Iface>.<method>") ... method.invoke(service, args); }
  finally { Thread.currentThread().setContextClassLoader(initial); }
  ```
- 顺带埋点：`killbill-service.kb_plugin_latency.<serviceName>.<Iface>.<method>`（Timer）和 `kb_plugin_errors...`（Meter）；`MetricRegistry` 本身不埋点，防止无限递归

### 1.5 Service Registry 实现（Kill Bill 自研抽象）

- `DefaultOSGIServiceDescriptor` —— `(pluginSymbolicName, pluginName, serviceName)`
- `ServiceRegistryServiceRegistration` —— `OSGIServiceRegistration<ServiceDiscoveryRegistry>`
- `MetricRegistryServiceRegistration` —— `OSGISingleServiceRegistration<MetricRegistry>`，单值语义 + `addRegistrationListener(Runnable)`
- `http/DefaultServletRouter` —— `OSGIServiceRegistration<Servlet>`

### 1.6 事件与总线

- **`KillbillEventObservable`** —— `extends java.util.Observable`，**用反射 hack 访问私有 `obs` 字段**，重写 `notifyObservers` 使 setChanged+notify 原子化。插件通过 `Observable` 这个 OSGi service 拿到它
- **`KillbillEventRetriableBusHandler`** —— 订阅 `@Named("externalBus") PersistentBus` 的 `OSGIBusEvent`，转成 `ExtBusEvent` 后推给 `KillbillEventObservable`；带重试队列 `extBusEvent-listener`
- **`OSGIListener`** —— 订阅 `BROADCAST_SERVICE` 事件，解析 `SystemNodeCommandType`，处理 `START_PLUGIN / STOP_PLUGIN / RESTART_PLUGIN`，调 `BundleRegistry`，再广播 topic `org/killbill/billing/osgi/plugin/<COMMAND>`，最后 `nodesApi.notifyPluginChanged(...)`
- **`OSGIAppender`** —— logback → OSGi `LogService`，用一个假的 `ServiceReference`（带 magic key `KILL_BILL_ROOT_LOGGING=true`）

### 1.7 插件配置（`pluginconf` 包，零 OSGi 依赖）

- **`PluginFinder`**（321 行，最核心的"发现"逻辑）。常量：
  `SELECTED_VERSION_LINK_NAME = "SET_DEFAULT"`、`TMP_DIR_NAME = "tmp"`、`DISABLED_FILE_NAME = "disabled.txt"`、`IDENTIFIERS_FILE_NAME = "plugin_identifiers.json"`
- **`DefaultPluginConfig`** —— `compareTo` 语义：`isSelectedForStart` 排最前，否则**版本字符串倒序**
  > ⚠️ **已知缺陷**：用 `String.compareTo` 比版本，导致 `"10.0" < "9.0"`
- **`DefaultPluginJavaConfig`** —— `extractJarPath()`：在版本目录下找**第一个 `.jar` 文件**
- **`PluginIdentifier`** —— `plugin_identifiers.json` 的 Jackson POJO
- **`DefaultPluginConfigServiceApi`** —— `Map<Long bundleId, PluginJavaConfig>` + 一个残留的 `Map<Long, PluginRubyConfig>`（永远为空）
- **`PuginConfServiceApi`** —— **空类，24 行，纯死代码**（类名还拼错了：Pugin）

### 1.8 配置：`config/OSGIConfig`

skife-config 接口，**是所有目录/系统包约定的唯一真相来源**：

| 属性 | 默认值 |
|---|---|
| `org.killbill.osgi.bundle.property.name` | `killbill.properties` |
| `org.killbill.osgi.root.dir` | `/var/tmp/felix`（Felix cache） |
| `org.killbill.osgi.bundle.cache.name` | `osgi-cache` |
| `org.killbill.osgi.bundle.install.dir` | **`/var/tmp/bundles`** |
| `org.killbill.osgi.system.bundle.export.packages.api` | ~50 个 `org.killbill.*` 包 + joda/shiro/slf4j/osgi.service.* |
| `org.killbill.osgi.system.bundle.export.packages.java` | ~150 个 `com.sun.xml.internal.ws.*` / `javax.*` / `jakarta.servlet;version=5.0` 等 |
| `org.killbill.billing.plugin.mandatory.plugins` | null |

> 那两坨巨大的 export 列表占整个文件约 90%，是 **OSGi ClassLoader 隔离模型的直接产物**。换成"parent-first 包前缀白名单"后可压缩到约 10 行。

### 1.9 Guice 装配：`glue/DefaultOSGIModule`

- `installConfig()` —— 绑 `OSGIConfig` + `OSGIConfigProperties`
- `installOSGIServlet()` —— `OSGIServiceRegistration<Servlet>` → `DefaultServletRouter`；`@Named("osgi") HttpServlet` → `OSGIServlet`（常量 `OSGI_NAMED = "osgi"`）
- `installDataSource()` —— `@Named(OSGI_DATA_SOURCE_ID) DataSource`
- `installOSGIComponents()` —— 全部 `asEagerSingleton`：
  `OSGIService→DefaultOSGIService`, `OSGIListener`, `BundleRegistry`, `FileInstall`, `KillbillActivator`, `PureOSGIBundleFinder`, `PluginFinder`, `PluginConfigServiceApi→DefaultPluginConfigServiceApi`, `OSGIKillbill→DefaultOSGIKillbill`, `KillbillEventObservable`, `KillbillEventRetriableBusHandler`, `PluginsInfoApi→DefaultPluginsInfoApi`

### 1.10 API 实现

- **`DefaultOSGIKillbill`** —— `implements OSGIKillbill`，20 多个 Core UserApi 的 Guice 注入 holder
- **`DefaultPluginsInfoApi`**：
  - `getPluginsInfo()`：合并 `pluginFinder.getAllPlugins()`（文件系统视图）和 `bundleRegistry`（运行时视图），再加 pure OSGI bundles
  - `notifyOfStateChanged(...)`：`NEW_VERSION` → `pluginFinder.reloadPlugins()`；`DISABLED` → `bundleRegistry.stopAndUninstallNewBundle(...)`；最后写 `node_infos` 表
  - `toPluginState(bundle)`：`bundle.getState() == Bundle.ACTIVE ? RUNNING : STOPPED`
    ← **唯一的状态机映射点，只有 2 个状态**

---

## 2. `osgi-api` 模块（8 main 文件 / 401 行）

| 文件 | 语义 | Felix 绑定？ |
|---|---|---|
| `OSGIServiceRegistrable<T>` | `registerService(desc, service)` / `unregisterService(name)` / `getServiceType()` / default `addRegistrationListener(Runnable)` | ❌ 纯自研 |
| `OSGIServiceRegistration<T>` | + `getServiceForName(name)` / `getAllServices()` | ❌ 纯自研 |
| `OSGISingleServiceRegistration<T>` | + `getService()`（只有 `MetricRegistry` 用） | ❌ 纯自研 |
| `OSGIServiceDescriptor` | 3 个 String getter | ❌ 纯自研 |
| `OSGIConfigProperties` | `getString` / `getProperties` / `getPropertiesBySource` | ❌ 纯自研 |
| `ROOSGIKillbillInterceptor` | JDK Proxy，把参数中的 `TenantContext` 换成 `ROTenantContext` | ❌ 纯自研 |
| `ROTenantContext` | 只读 TenantContext 包装 | ❌ 纯自研 |
| **`OSGIKillbillRegistrar`** | `Map<String, ServiceRegistration>` + `registerService(BundleContext, ...)` | ✅ **唯一强绑定** |

> **`osgi-api` 里只有 `OSGIKillbillRegistrar` 一个文件需要重写。**

### 相关接口实际在 `killbill-api` 仓库

路径：`killbill-api/src/main/java/org/killbill/billing/osgi/api/`

| 接口/枚举 | 语义 |
|---|---|
| `OSGIKillbill` | 插件访问 Core 的总门面：16 个 UserApi + `getPluginConfigServiceApi()` + `getSecurityApi()` + `getPluginsInfoApi()` + `getKillbillNodesApi()` + `getAdminPaymentApi()` |
| `OSGIPluginProperties` | 只有一个常量 `PLUGIN_NAME_PROP = "killbill.pluginName"` ← **整个服务发现的 key** |
| `PluginsInfoApi` | `getPluginsInfo()` + `notifyOfStateChanged(...)` |
| `PluginInfo` | `pluginKey / bundleSymbolicName / pluginName / version / PluginState / Set<PluginServiceInfo> / isSelectedForStart` |
| `PluginServiceInfo` | `serviceTypeName` + `registrationName` |
| `PluginState` | `STOPPED, RUNNING`（**只有 2 个状态**） |
| `PluginStateChange` | `NEW_VERSION, DISABLED`（**只有 2 个**） |
| `ServiceDiscoveryRegistry` | `register()` / `unregister()`（Eureka 用） |
| `Healthcheck` | `getHealthStatus(Tenant, Map)` |

`killbill-api/.../osgi/api/config/`：

| 文件 | 语义 |
|---|---|
| `PluginConfig` | `pluginKey / pluginName / PluginType / version / pluginVersionnedName / File pluginVersionRoot / PluginLanguage / isSelectedForStart / isDisabled` |
| `PluginJavaConfig` | + `getBundleJarPath()` |
| `PluginRubyConfig` | **僵尸接口，无实现** |
| `PluginConfigServiceApi` | `getPluginJavaConfig(long bundleId)` ← **`bundleId` 是 OSGi 概念，必须改签名** |
| `PluginLanguage` | `JAVA, RUBY` |
| `PluginType` | `PAYMENT, NOTIFICATION, INVOICE, CURRENCY, PAYMENT_CONTROL, CATALOG, ENTITLEMENT, USAGE, __UNKNOWN__` |

---

## 3. `osgi-bundles` 模块（78 main 文件 / 9480 行）

| 子模块 | 文件/行 | 职责 |
|---|---|---|
| **`libs/killbill`** | 14 / 1508 | **插件 SDK**（所有第三方插件都依赖它） |
| `libs/slf4j-osgi` | 4 / 876 | slf4j 1.7 binder，桥到 OSGi `LogService` |
| `bundles/kpm` | 34 / 3756 | KPM 插件管理器 |
| `bundles/logger` | 7 / 1195 | 日志采集 + SSE 推送 |
| `bundles/metrics` | 1 / 124 | 创建 dropwizard `MetricRegistry` |
| `bundles/prometheus` | 4 / 334 | 暴露 `/plugins/killbill-prometheus` |
| `bundles/influxdb` | 4 / 434 | 定时推 InfluxDB |
| `bundles/graphite` | 2 / 140 | 定时推 Graphite |
| `bundles/eureka` | 3 / 288 | 实现 `ServiceDiscoveryRegistry` |
| `tests/beatrix` | 3 / 465 | 测试 bundle |
| `tests/payment` | 2 / 360 | 测试 bundle |
| `defaultbundles` | pom-only | assembly，把 kpm+logger+metrics 打成 tar.gz |

### 3.1 `libs/killbill` —— 插件 SDK

| 文件 | 职责 |
|---|---|
| **`KillbillActivatorBase`** | 所有 Kill Bill 插件的 Activator 基类。`start(BundleContext)` 里 new 出 `killbillAPI / roOSGIkillbillAPI / dataSource / dispatcher / configProperties / clock / metricRegistry / healthCheckRegistry / registrar`；`setupTmpDir()` 建 `<pluginVersionRoot>/tmp/`；`setupRestartMechanism()` 起 ScheduledExecutor 每 N 秒检查 `tmp/disabled.txt`（停）和 `tmp/restart.txt` 的 mtime（重启，Passenger 风格热更） |
| `OSGIKillbillAPI` | `implements OSGIKillbill`，用 `ServiceTracker<OSGIKillbill>` 逐方法代理 |
| `ROOSGIKillbillAPI` | 每个方法再套 `ROOSGIKillbillInterceptor` 代理 |
| **`OSGIKillbillEventDispatcher`** | 事件订阅核心。跟踪 `java.util.Observable` service；`registerEventHandlers(OSGIHandlerMarker...)`；内部 `Observer` 回调前后切 TCCL；分发 `OSGIKillbillEventHandler.handleKillbillEvent(ExtBusEvent)` 和 `OSGIFrameworkEventHandler.started()` |
| `OSGIConfigPropertiesService` / `OSGIKillbillDataSource` / `OSGIKillbillClock` / `OSGIMetricRegistry` | tracker 代理 |
| `OSGIKillbillLogService` | `@Deprecated` |
| `KillbillServiceListener` | `context.addServiceListener(l, "(objectclass=X)")` + 补发已注册事件 |

> **12/14 个文件强绑定 `org.osgi.framework.BundleContext` / `ServiceTracker`。**
> 由于本次改造会重写全部插件，**不需要兼容层**，直接重新设计为 `lpr-api` 的 `Plugin` / `PluginContext`。

### 3.2 JRuby 桥接 —— 已完全移除

搜索 `jruby` 只剩 3 处非代码残留：
1. `README.md:9`（文档过期）
2. `platform-test/.../SetupBundleWithAssertion.java` 的 `setupJrubyBundle()` / `installJrubyJar()`（测试里的死路径，无对应 bundle 模块）
3. `bundles/logger/.../LogEntryJson.java` 里的字符串

代码残迹：`PluginLanguage.RUBY` 枚举值、`PluginRubyConfig` 接口（无实现）、`DefaultPluginConfigServiceApi.rubyConfigMappings`（永远空 Map）、`PluginFinder.extractPluginConfig()` 的死分支、`FileInstall.installBundle()` 的 `switch default`。

**结论：迁移时是纯删除项，无需设计对等能力。**

### 3.3 Bundle 打包机制

所有 bundle 用 `<packaging>bundle</packaging>` + `maven-bundle-plugin`（bnd）。指令模板在 `killbill-oss-parent/pom.xml:1716-1744`：

```xml
<osgi.activator>$${classes;EXTENDS;org.killbill.billing.osgi.libs.killbill.KillbillActivatorBase}</osgi.activator>
<osgi.dependency>*;scope=compile|runtime;inline=true</osgi.dependency>   <!-- 依赖全部 inline 进 fat bundle -->
<osgi.import>...约 55 个 org.killbill.* 包 ... , *;resolution:=optional</osgi.import>
```

> `Bundle-Activator` 是 bnd **自动扫描"继承 `KillbillActivatorBase` 的类"**生成的。新运行时需要一个显式的**插件描述符**（`plugin.yaml`）取代这种隐式扫描。
>
> **有利的一点**：插件 jar 是 **fat jar（依赖 inline）**，对自研 ClassLoader 隔离天然友好——不需要依赖解析器。

---

## 4. 插件的发现与加载

### 4.1 目录布局（现状）

根目录：`org.killbill.osgi.bundle.install.dir`，默认 **`/var/tmp/bundles`**

```
/var/tmp/bundles/
├── platform/                                  ← PureOSGIBundleFinder 扫这里（非递归）
│   ├── killbill-platform-osgi-bundles-kpm-*.jar
│   ├── killbill-platform-osgi-bundles-logger-*.jar
│   └── killbill-platform-osgi-bundles-metrics-*.jar
└── plugins/
    ├── plugin_identifiers.json                ← pluginKey → PluginIdentifier 映射
    └── java/                                  ← PluginFinder 扫这里
        └── <pluginName>/                      ← 目录名 = pluginName（KPM 约定 = "<pluginKey>-plugin"）
            ├── SET_DEFAULT -> 2.1/            ← 符号链接，指向"选中启动"的版本
            ├── 1.0/
            ├── 2.1/
            │   ├── <anything>.jar             ← 版本目录下第一个 .jar
            │   ├── killbill.properties        ← 可选，目前只读 pluginType
            │   └── tmp/
            │       ├── disabled.txt           ← 存在 → 该版本被跳过 / 运行中的插件自停
            │       └── restart.txt            ← mtime 变化 → 插件热重启
            └── 2.2/
```

另有 Felix cache：`/var/tmp/felix/osgi-cache`，每次 `initialize()` **整个清空**。

### 4.2 `SET_DEFAULT` 版本选择算法

1. `resolveVersionToStartLink()` —— 读 `SET_DEFAULT` 的 `getCanonicalFile().getName()`
2. 扫所有版本子目录，跳过 `SET_DEFAULT` 本身，跳过 `tmp/disabled.txt` 存在的版本
3. `Collections.sort()`：`isSelectedForStart` 排第一，其余按**版本字符串倒序**（⚠️ `"10.0" < "9.0"` 缺陷）
4. 列表首元素被重建为 `isSelectedForStart=true`
5. 若某插件所有版本都 disabled → 从 `allPlugins` 移除

`getLatestJavaPlugins()` 每个 pluginName 只返回 `plugins.get(0)`，即**每个插件同时只启动一个版本**。

### 4.3 `plugin_identifiers.json`

- **写方**：KPM 的 `FileBasedPluginIdentifiersDAO`（`PropertyNamingStrategies.SNAKE_CASE`）
- **读方**：`PluginFinder.readPluginIdentifiers()`
- 格式：`{ "<pluginKey>": { "plugin_name": "...", "group_id": "...", "artifact_id": "...", "version": "...", "language": "..." } }`
- 用途：`pluginKey ↔ pluginName` 双向解析。**API 层用 pluginKey（如 `stripe`），文件系统用 pluginName（如 `stripe-plugin`）**

### 4.4 KPM 的角色

KPM 是**一个普通的 Kill Bill 插件**，不是 Core 的一部分。

- `Activator` —— plugin name `killbill-kpm`；注册 jooby-based `Servlet`（`GET /plugins/killbill-kpm/plugins`）；注册 `EventsListener`
- `EventsListener` —— 监听 `BROADCAST_SERVICE`，处理 `INSTALL_PLUGIN` / `UNINSTALL_PLUGIN`
- `DefaultPluginManager` install 流程：
  1. `createTmpDownloadPath()`
  2. `KPMClient.download(uri, file)` 或 Nexus coordinate 解析（`CoordinateBasedPluginDownloader` + `DefaultNexusMetadataFiles` + `Sha1Checker`）
  3. `DefaultPluginInstaller.install(...)` → 建 `<bundles>/plugins/java/<name>/<version>/` + `createSymlink()` 建 `SET_DEFAULT`
  4. `pluginIdentifiersDAO.add(pluginKey, version)` 写 json
  5. **登录 admin 后调 `notifyOfStateChanged(...)`**
- `PluginNamingResolver` —— **命名约定的唯一实现**：`getPluginName() = pluginKey + "-plugin"`；版本用正则 `(\d+[1-9]*).(\d+).(\d+)(?:-([a-zA-Z0-9]+))?` 抠取，抠不到就 `"0.0.0"`
- `KpmProperties` —— nexus URL 默认 `https://repo1.maven.org/maven2`；`nexusAuthMethod`（NONE/BASIC/TOKEN）

### 4.5 完整的插件安装 → 启动链路

```
REST POST /1.0/kb/nodesInfo (type=INSTALL_PLUGIN)
  → killbillInfoApi.triggerNodeCommand()      [killbill/jaxrs/.../NodesInfoResource.java:152]
  → externalBus 广播 BROADCAST_SERVICE
  → KPM 的 EventsListener → DefaultPluginManager.install()
       → 下载 jar，落盘，建 SET_DEFAULT，写 plugin_identifiers.json
       → PluginsInfoApi.notifyOfStateChanged(NEW_VERSION, ...)
            → PluginFinder.reloadPlugins() + nodesApi.notifyPluginChanged()
REST POST /1.0/kb/nodesInfo (type=START_PLUGIN)
  → externalBus 广播
  → OSGIListener.handleKillbillEvent()
       → BundleRegistry.installAndStartNewBundle(pluginName, version)
            → FileInstall.installNewBundle() → context.installBundle("file:...")
            → bundle.start() → 插件 Activator.start()
       → killbillActivator.sendEvent("org/killbill/billing/osgi/plugin/START_PLUGIN", {...})
```

---

## 5. 插件与 Core 的通信面

### 5.1 插件拿 Core API

```
KillbillActivator.start()  [System Bundle Activator]
  registrar.registerService(context, OSGIKillbill.class, defaultOSGIKillbill, {killbill.pluginName=killbill})
        ↓ OSGi Service Registry
KillbillActivatorBase.start()  [插件侧]
  killbillAPI = new OSGIKillbillAPI(context)
        ↓ ServiceTracker<OSGIKillbill>
  killbillAPI.getPaymentApi()  → tracker.getService().getPaymentApi()
```

同机制的还有 7 个：`Observable`、`DataSource`、`OSGIConfigProperties`、`Clock`、`MetricRegistry`、`HealthCheckRegistry`、`LogService`。

> **新运行时的等价物**：一个 `PluginContext` 直接持有这 7~8 个 Core 单例即可，不需要 ServiceTracker 的异步/延迟语义（Core 的这些服务在插件启动前必定就绪）。

### 5.2 插件注册服务给 Core

插件侧：
```java
props.put(OSGIPluginProperties.PLUGIN_NAME_PROP, "test");     // = "killbill.pluginName"
registrar.registerService(context, PaymentPluginApi.class, new TestPaymentPluginApi(dao), props);
```

Core 侧 12 个 `OSGIServiceRegistration` 实现：

| 服务类型 | 实现类 | 位置 |
|---|---|---|
| `Servlet` | `DefaultServletRouter` | platform/osgi |
| `ServiceDiscoveryRegistry` | `ServiceRegistryServiceRegistration` | platform/osgi |
| `MetricRegistry`（Single） | `MetricRegistryServiceRegistration` | platform/osgi |
| `Healthcheck` | `DefaultHealthcheckPluginRegistry` | killbill/profiles/killbill |
| `PaymentPluginApi` | `DefaultPaymentProviderPluginRegistry` | killbill/payment |
| `PaymentControlPluginApi` | (Provider) | killbill/payment |
| `InvoicePluginApi` / `InvoiceFormatterFactory` | (Provider) | killbill/invoice |
| `CatalogPluginApi` | (Provider) | killbill/catalog |
| `CurrencyPluginApi` | (Provider) | killbill/currency |
| `EntitlementPluginApi` | (Provider) | killbill/entitlement |
| `UsagePluginApi` | (Provider) | killbill/usage |

> **这一层完全没有 OSGi 依赖**，`OSGIServiceRegistration` 只是一个 `Map<String, T>` 的接口。

### 5.3 HTTP servlet 注册与分发

**注册**：插件 `registerService(context, Servlet.class, httpServlet, {killbill.pluginName=<name>})` → `DefaultServletRouter.registerService(desc, servlet)`，`pathPrefix = sanitizePathPrefix(desc.getRegistrationName())`

**分发**：
```
HTTP GET /plugins/killbill-kpm/plugins
  → killbill/jaxrs/.../PluginResource.java  @Path(PLUGINS_PATH + "{subResources:.*}")
       @Named("osgi") HttpServlet osgiServlet
       request.setAttribute("killbill.osgi.servletConfig", servletConfig)
       osgiServlet.service(OSGIServletRequestWrapper, OSGIServletResponseWrapper)
  → platform/osgi/.../http/OSGIServlet.java
       servletRouter.getServiceForPath(requestPath)  → 最长前缀匹配
       initializeServletIfNeeded()  // 首次调用时 init；注释: TODO 永远不会 destroy
       pluginServlet.service(new OSGIServletRequestWrapper(req, pluginPrefix), resp)
       找不到 → 404
```

> 这一层唯一的 OSGi 强绑定是 `StaticServlet` 的 `org.osgi.service.http.HttpContext`。
> `OSGIServlet` + `DefaultServletRouter` 是纯 servlet API，**可原样搬运**。

---

## 6. `lifecycle` 模块

`DefaultLifecycle` 用 `ServiceFinder<KillbillService>` classpath 扫描找到所有 service → 反射扫 `@LifecycleHandlerType` 注解方法 → 按 `LifecycleLevel` 分桶 → `fireSequence(seq)` 按枚举声明顺序执行。

### `LifecycleLevel` 枚举（顺序即执行顺序）

| Level | Sequence | 与 OSGi 的关系 |
|---|---|---|
| `BOOT` | STARTUP_PRE | |
| `LOAD_CATALOG` | STARTUP_PRE | |
| `INIT_BUS` | STARTUP_PRE | |
| **`INIT_PLUGIN`** | STARTUP_PRE | **javadoc 直写 "Start Felix Framework along with its system bundle"** |
| `INIT_SERVICE` | STARTUP_PRE | `KillbillEventRetriableBusHandler.initialize()` |
| **`START_PLUGIN`** | STARTUP_PRE | "Start all the plugins" |
| `START_SERVICE` | STARTUP_POST | |
| `START_BUS` | STARTUP_POST | |
| `STOP_SERVICE` | SHUTDOWN_PRE | |
| **`STOP_PLUGIN`** | SHUTDOWN_PRE | |
| `STOP_BUS` | SHUTDOWN_POST | |
| `SHUTDOWN` | SHUTDOWN_POST | |

> **关键顺序语义**：`START_PLUGIN` 属于 STARTUP_**PRE** 序列，**插件在所有 Core service 的 `START_SERVICE` 之前就启动完了**。
> 所以插件 Activator 里不能立刻调用 Core API 做业务，必须等 `OSGIFrameworkEventHandler.started()`（`.../lifecycle/STARTED` topic，在 `DefaultOSGIService.start()` 末尾发出）。
> **LPR 必须保留这个语义，否则插件会在 Core 未就绪时崩。**

`KillbillService.KILLBILL_SERVICES` 中 `OSGI_SERVICE("osgi-service", 520)` 排最后。

---

## 7. `server` 模块

`web.xml` → `KillbillPlatformGuiceListener`（`extends GuiceServletContextListener`）：

```
contextInitialized():
  initializeConfig()
  initializeGuice(event)  → [ServletModule, JMXModule, StatsModule, KillbillPlatformModule]
  initializeMetrics(event)
  startLifecycle():
      fireStartupSequencePriorEventRegistration()   ← 这里跑 INIT_PLUGIN + START_PLUGIN
      fireStartupSequencePostEventRegistration()
contextDestroyed():
  putOutOfRotation() → stopLifecycle() → stopEmbeddedDBs()（osgi → shiro → main）
```

`KillbillPlatformModule.configure()` 依次调用 `configureJackson / Clock / Dao / Config / EmbeddedDBs / Lifecycle / Buses / NotificationQ / **configureOSGI** / JNDI / Metrics`。

```java
protected void configureOSGI() {
    install(new DefaultOSGIModule(configSource, (DefaultKillbillConfigSource) configSource,
                                  osgiDataSourceConfig, osgiEmbeddedDB));
}
```

`configureMetrics()` 把全局 `MetricRegistry` 绑成 `KillbillPluginsMetricRegistry`（转发给插件提供的 MetricRegistry 的 façade）。

---

## 8. 代码规模统计

| 模块 | main 文件 | main 行 | test 文件 | test 行 |
|---|---:|---:|---:|---:|
| **osgi** | **31** | **4,402** | 5 | 533 |
| **osgi-api** | **8** | **401** | 1 | 32 |
| **osgi-bundles** | **78** | **9,480** | 18 | 1,560 |
| **三者小计** | **117** | **14,283** | **24** | **2,125** |
| lifecycle | 12 | 1,135 | 1 | 247 |
| base | 10 | 1,187 | 3 | 491 |
| platform-api | 5 | 305 | 0 | 0 |
| platform-test | 5 | 612 | 6 | 1,228 |
| server | 21 | 2,743 | 2 | 497 |
| **platform 全量** | **170** | **20,265** | **36** | **4,588** |

### Felix/OSGi 强绑定分布（`import org.osgi.*` 或 `org.apache.felix.*`）

| 模块 | 强绑定文件数 | 具体 |
|---|---:|---|
| **osgi** | 10 | `DefaultOSGIService`、`FileInstall`、`BundleRegistry`、`BundleWithConfig`、`KillbillActivator`、`OSGIListener`、`OSGIAppender`、`api/DefaultPluginsInfoApi`、`http/StaticServlet` |
| **osgi-api** | 1 | `OSGIKillbillRegistrar` |
| **osgi-bundles** | 29 | libs/killbill 12 + libs/slf4j-osgi 3 + 各 bundle Activator 8 + logger 4 + tests 2 |
| killbill core | 1 | 仅测试文件 `TestWithFakeKPMPlugin` |
| killbill-api / killbill-plugin-api | **0** | — |

### `osgi` 模块中零 OSGi 依赖、可直接复用的 21/31 个文件

`ContextClassLoaderHelper`、`DefaultOSGIKillbill`、`DefaultOSGIServiceDescriptor`、`KillbillEventObservable`、`KillbillEventRetriableBusHandler`、`MetricRegistryServiceRegistration`、`ServiceRegistryServiceRegistration`、`config/OSGIConfig`、`glue/DefaultOSGIModule`、`glue/OSGIDataSourceConfig`、`http/DefaultServletRouter`、`http/OSGIServlet`、`api/KillbillNodesApiHolder`、`api/KillbillEventRetriableBusHandlerService`、`pluginconf/*`（7 个）

---

## 9. 迁移映射表

### 必须替换（Felix / OSGi 规范）

| 现有 | 自研 LPR 对应物 |
|---|---|
| `Felix` + `Framework.init/start/stop` | `PluginRuntime` 启停 |
| `org.osgi.framework.system.packages.extra`（两坨 200 行包名） | parent-first 包前缀白名单（约 10 行） |
| `felix.cache.rootdir` + `pruneOSGICache()` | **可直接删除** |
| `org.osgi.framework.bundle.parent=ext` | ClassLoader parent 选择 |
| `BundleContext.installBundle` / `Bundle.start/stop` / `getState()` | `PluginLoader` + 8 态状态机（现只有 2 态） |
| `BundleActivator`（system + 插件侧） | `Plugin` SPI |
| `ServiceRegistration` / `ServiceEvent` / `AllServiceListener` | Service Registry + 注册回调 |
| `ServiceTracker`（libs/killbill 8 个 wrapper） | `PluginContext` 直接持有引用 |
| `org.osgi.service.event.Event` + `java.util.Observable` | LPR EventBus（topic 常量语义要保留） |
| `LogService` + `OSGIAppender` + `libs/slf4j-osgi` | slf4j 直连 |
| `org.osgi.service.http.HttpContext` | classloader `getResource()` |
| `maven-bundle-plugin` + bnd 指令 | `plugin.yaml` 描述符 |
| `BundleRevision.TYPE_FRAGMENT` | **可直接删除** |

### 可整体保留

- `killbill-api` 的 `org.killbill.billing.osgi.api.*` 全部（唯一要改签名的是 `PluginConfigServiceApi.getPluginJavaConfig(long bundleId)`）
- `osgi-api` 的 7/8 个接口
- `ContextClassLoaderHelper`（**仍然必需**）
- `pluginconf` 包（目录扫描约定，与 OSGi 无关）
- `http/DefaultServletRouter` + `http/OSGIServlet`
- 整个 `lifecycle` / `platform-api` / `base` / `server`（除 `configureOSGI()` 一行）
- Core repo 里 12 个 `OSGIServiceRegistration<T>` 实现

### 死代码 / 文档陈旧（直接删）

- `pluginconf/PuginConfServiceApi.java`（空类，类名拼错）
- `PluginLanguage.RUBY` / `PluginRubyConfig` / `rubyConfigMappings` / `PluginFinder` RUBY 分支
- `SetupBundleWithAssertion.setupJrubyBundle()` / `installJrubyJar()`
- `README.md:9` 的 JRuby bridge 描述
- `OSGIKillbillLogService`（已 `@Deprecated`）+ `libs/slf4j-osgi`
- `http/StaticServlet.java`（未被 `DefaultOSGIModule` 绑定）
