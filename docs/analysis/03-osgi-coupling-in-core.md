# 分析报告 03：killbill 业务核心对 OSGi 的耦合面

> 调研对象：`killbill` 主工程（16 个业务模块）+ `killbill-plugin-api`
> 目的：量化"替换插件运行时需要改动多少地方"，并区分机械替换 vs 真实现改造

---

> ⚠️ **这是改造前的快照。** 报告里描述的 OSGi 代码**已被全部删除**（141 文件 / 16408 行）。
> 保留它是因为它记录了"当时是什么样、为什么这么改"的依据；不要把它当作现状来读。
> 现状见 [`../migration/osgi-to-lpr-plan.md`](../migration/osgi-to-lpr-plan.md) 的附录三、四。



## 0. 关键结论

主工程**几乎不直接依赖 Felix / `org.osgi.*`**。真正的耦合点只有两类：

1. **类型层面**：到处注入 `OSGIServiceRegistration<XxxPluginApi>`（来自 `killbill-platform/osgi-api`），这是一个**纯 Java 泛型注册表接口，零 `org.osgi` 依赖**。
   全主工程 main 源码里 `import org.osgi.*` 是 **0 处**。
2. **装配层面**：`profiles/killbill` 通过继承 `KillbillPlatformModule` 间接 `install(new DefaultOSGIModule(...))`，以及 `jaxrs/PluginResource` 注入 `@Named("osgi") HttpServlet`。

> **替换插件运行时的主战场在 killbill-platform，主工程只需要换掉「注册表接口的来源 + 3 个装配点」。**

---

## 1. 全仓库扫描结果

### 1.1 统计概览

| 维度 | 数量 |
|---|---|
| 主工程 main 源码引用 `org.killbill.billing.osgi.*` 的文件 | **46** |
| 主工程 test 源码引用的文件 | **32** |
| `OSGIServiceRegistration` 出现次数 | main **94** / test **53** |
| `OSGIServiceDescriptor` 出现次数 | main **25** / test **33** |
| 主工程 main 源码中 `import org.osgi.*` | **0** |
| pom 中依赖 `killbill-platform-osgi` 或 `-osgi-api` 的模块 | **9** |

### 1.2 import 类型分布（去重计数，全仓 124 处）

```
  60  import org.killbill.billing.osgi.api.OSGIServiceRegistration
  28  import org.killbill.billing.osgi.api.OSGIServiceDescriptor
   7  import org.killbill.billing.osgi.api.PluginInfo
   4  import org.killbill.billing.osgi.api.PluginsInfoApi
   3  import org.killbill.billing.osgi.api.Healthcheck
   2  import org.killbill.billing.osgi.http.DefaultServletRouter
   2  import org.killbill.billing.osgi.config.OSGIConfig
   2  import org.killbill.billing.osgi.api.ServiceDiscoveryRegistry
   2  import org.killbill.billing.osgi.api.ROTenantContext
   2  import org.killbill.billing.osgi.api.PluginState
   1  import org.killbill.billing.osgi.pluginconf.PluginFinder
   1  import org.killbill.billing.osgi.pluginconf.PluginConfigException
   1  import org.killbill.billing.osgi.FileInstall
   1  import org.killbill.billing.osgi.BundleWithConfig
   1  import org.killbill.billing.osgi.BundleRegistry
   1  import org.killbill.billing.osgi.api.PluginStateChange
   1  import org.killbill.billing.osgi.api.PluginServiceInfo
   1  import org.killbill.billing.osgi.api.OSGISingleServiceRegistration
   1  import org.killbill.billing.osgi.api.DefaultPluginsInfoApi.DefaultPluginServiceInfo
   1  import org.killbill.billing.osgi.api.DefaultPluginsInfoApi.DefaultPluginInfo
   1  import org.killbill.billing.osgi.api.config.PluginType
   1  import org.killbill.billing.osgi.api.config.PluginLanguage
   1  import org.killbill.billing.osgi.api.config.PluginConfig
```

**88 处（71%）只是 `OSGIServiceRegistration` + `OSGIServiceDescriptor` 这两个零 OSGi 依赖的纯接口。**

### 1.3 使用模式：统一是"注入 registry → getServiceForName / getAllServices"

```java
// killbill-platform/osgi-api/.../OSGIServiceRegistrable.java
public interface OSGIServiceRegistrable<T> {
    void registerService(OSGIServiceDescriptor desc, T service);
    void unregisterService(String serviceName);
    default void addRegistrationListener(Runnable listener) { throw new UnsupportedOperationException(); }
    Class<T> getServiceType();
}
// OSGIServiceRegistration.java
public interface OSGIServiceRegistration<T> extends OSGIServiceRegistrable<T> {
    T getServiceForName(String serviceName);
    Set<String> getAllServices();
}
// OSGIServiceDescriptor.java —— 只有 3 个 String getter
public interface OSGIServiceDescriptor {
    String getPluginSymbolicName();
    String getPluginName();
    String getRegistrationName();
}
```

**可裁剪项**：
- `addRegistrationListener` 在主工程 main 源码里 **0 调用**
- `OSGISingleServiceRegistration<T>` 在 main 里 **0 处**（只在 beatrix 测试的 `MetricRegistry` binding 出现 2 次）

---

## 2. 各业务域的插件调用入口

### 2.1 payment —— `PaymentPluginApi`

**入口类**：`payment/src/main/java/org/killbill/billing/payment/core/PaymentPluginServiceRegistration.java`

```java
public PaymentPluginApi getPaymentPluginApi(final String pluginName) throws PaymentApiException {
    final PaymentPluginApi pluginApi = pluginRegistry.getServiceForName(pluginName);
    if (pluginApi == null) {
        throw new PaymentApiException(ErrorCode.PAYMENT_NO_SUCH_PAYMENT_PLUGIN, pluginName);
    }
    return pluginApi;
}
```

**调用链**（全仓最大扇出面，`PaymentPluginApi` 在 payment/main 里约 30 个引用点）：
```
PaymentPluginServiceRegistration
  ↑ ProcessorBase.getPaymentPluginApi(String)         core/ProcessorBase.java:106-111
      ↑ PaymentProcessor                              :287
      ↑ PaymentMethodProcessor                        :131,344,371,434,490,529,559,589
      ↑ PaymentGatewayProcessor                       :82,109
      ↑ PaymentRefresher                              :192,195,287-334,400,457,625,638
  ↑ PaymentAutomatonDAOHelper.getPaymentPluginApi()   core/sm/:244-271
      ↑ PaymentOperation.doOperation()                core/sm/payments/:48,62
  ↑ IncompletePaymentTransactionTask                  core/janitor/:248
```

**超时/线程隔离**：`payment/dispatcher/PluginDispatcher<ReturnType>`
- `dispatchWithTimeout(task)` / `dispatchWithTimeout(task, timeout, unit)`
- 依赖 `PaymentExecutors` 线程池
- **这一层跟 OSGi 完全无关，可原样保留**

**内建 plugin**：`provider/ExternalPaymentProviderPlugin implements PaymentPluginApi`，由 `DefaultPaymentProviderPluginRegistryProvider.get()` 在 Provider 里直接 `registerService`。

### 2.2 payment control —— `PaymentControlPluginApi`

**入口类**：`payment/core/sm/control/ControlPluginRunner.java`
- `:114` `executePluginPriorCalls(...)` → `getServiceForName(controlPluginName)` → `plugin.priorCall(...)`
- `:199` `executePluginOnSuccessCalls(...)`
- `:267` `executePluginOnFailureCalls(...)`

特点：**按 `paymentControlPluginNames` 列表顺序串行执行，链式传递结果**（`prevResult.getAdjustedPaymentMethodId()` 等）；插件缺失只 `log.warn` 跳过。

**内建 control plugin** 2 个：`ExternalPaymentControlProviderPlugin`、`InvoicePaymentControlPluginApi`。
**旁路入口**：`payment/invoice/PaymentTagHandler.java:56` 构造期直接抓 `InvoicePaymentControlPluginApi.PLUGIN_NAME`。

### 2.3 invoice —— `InvoicePluginApi`

**入口类**：`invoice/src/main/java/org/killbill/billing/invoice/InvoicePluginDispatcher.java`

| 方法 | 行号 | 调用的 SPI |
|---|---|---|
| `priorCall(...)` | :113 | `InvoicePluginApi.priorCall` (:133) |
| `onSuccessCall(...)` | :159 | → `onCompletionCall` → `onSuccessCall` (:207) |
| `onFailureCall(...)` | :171 | → `onFailureCall` (:212) |
| `splitInvoices(...)` | :243 | `getInvoiceGrouping` (:258) |
| `updateOriginalInvoiceWithPluginInvoiceItems(...)` | :323 | `getAdditionalInvoiceItems` (:350) |
| `getInvoicePlugins(...)` | :466 | 查表 |
| `getResultingPluginNameList(...)` | :478 | **多租户排序**：`invoiceConfig.getInvoicePluginNames(tenantContext)` 决定顺序，配置为空则退回 `getAllServices()`（顺序不确定） |

额外校验：`validateAndSanitizeInvoiceItemFromPlugin` (:388)、`mutableField` (:443)、`immutableField` (:453)、`ALLOWED_INVOICE_ITEM_TYPES` (:79)。

**`InvoiceFormatterFactory` 单独一条线**：`invoice/template/HtmlInvoiceGenerator.java:106,111-112` —— 先按配置名找，找不到且只注册了 1 个就用那一个，否则用内建 formatter。

### 2.4 catalog —— `CatalogPluginApi`

**入口类**：`catalog/src/main/java/org/killbill/billing/catalog/caching/DefaultCatalogCache.java`
- `getCatalogFromPlugins(InternalTenantContext)` **:145**
- `:147` `getAllServices()` → `:149` `getServiceForName(service)`
- `:159` `plugin.getLatestCatalogVersion(...)`（返回 null 则跳过缓存）
- `:171` `plugin.getVersionedPluginCatalog(...)`
- 语义：**第一个返回非 null 的插件胜出**

### 2.5 entitlement —— `EntitlementPluginApi`

**入口类**：`entitlement/src/main/java/org/killbill/billing/entitlement/api/EntitlementPluginExecution.java`
- `executePluginPriorCalls(...)` :109-127 —— 遍历 `getAllServices()`，链式传 `DefaultEntitlementContext(currentContext, prevResult)`，`isAborted()` 则 break
- `executePluginOnSuccessCalls(...)` :131 / `executePluginOnFailureCalls(...)` :141
- 异常映射：`EntitlementPluginApiException` → `ErrorCode.ENT_PLUGIN_API_ABORTED` (:105)

### 2.6 currency —— `CurrencyPluginApi`

**入口类**：`currency/src/main/java/org/killbill/billing/currency/api/DefaultCurrencyConversionApi.java`
- `:44` `registry.getServiceForName(config.getDefaultCurrencyProvider())` —— **只用单个配置指定的插件，不遍历**

### 2.7 usage —— `UsagePluginApi`

**入口类**：`usage/src/main/java/org/killbill/billing/usage/api/BaseUserApi.java`
- `getUsageFromPlugin(...)` :63 —— :66 `getAllServices()`（空则返回 null 走 DB），:72 `getServiceForName()`，:74-76 调 `getUsageForSubscription` / `getUsageForAccount`
- 语义：**第一个返回非 null 的插件胜出**；带 `DebugMap` 日期范围校验
- 子类：`api/user/DefaultUsageUserApi.java:64`、`api/svcs/DefaultInternalUserApi.java:57`

### 2.8 notification —— 架构不一致点 ⚠️

**主工程侧完全没有代码**。链路全在 platform：

```
beatrix/glue/BeatrixModule.java:35 installExternalBus()        ← 唯一相关点
   ↓  (ExtBusEvent 发到 externalBus)
platform/osgi/KillbillEventRetriableBusHandler.java
   @Subscribe handleKillbillEvent(ExtBusEvent) :125-128
   → RetryableSubscriber → SubscriberAction<OSGIBusEvent>.run() :77-83
   → KillbillEventObservable.setChangedAndNotifyObservers(event)
platform/osgi/KillbillEventObservable.java  (extends java.util.Observable，反射 hack 访问 obs 字段)
   ↓  registrar.registerService(context, Observable.class, observable, props)   ← KillbillActivator:207
platform/osgi-bundles/libs/killbill/OSGIKillbillEventDispatcher.java
   实现 Observer.update()，:83-93 分派给 OSGIKillbillEventHandler.handleKillbillEvent(ExtBusEvent)
   ↑ KillbillActivatorBase:55,75 每个插件 bundle 自动创建 dispatcher
```

> **`NotificationPluginApi` 在主工程和 platform 的 registry 里都没有对应的 `OSGIServiceRegistration<NotificationPluginApi>`** —— 插件通过 `OSGIKillbillEventHandler` 回调机制收事件，**不走 service registry**。
>
> 这是和其他 7 个域**架构不一致**的地方，迁移时应借机统一。

### 2.9 jaxrs —— 插件 HTTP 端点转发（唯一强耦合点）

**入口类**：`jaxrs/src/main/java/org/killbill/billing/jaxrs/resources/PluginResource.java`

```java
@Path(JaxrsResource.PLUGINS_PATH + "{subResources:.*}")   // :86, PLUGINS_PATH = "/plugins"
public class PluginResource extends JaxRsResourceBase {
    private final HttpServlet osgiServlet;                                     // :93
    @Inject
    public PluginResource(@Named("osgi") final HttpServlet osgiServlet, ...)   // :96
```

- HTTP 方法：`doDELETE`(:111) / `doGET`(:120) / `doOPTIONS`(:129) / `doFormPOST`(:139) / `doPOST`(:149) / `doPUT`(:158) / `doHEAD`(:167)，全部转 `serviceViaOSGIPlugin(...)`(:178/:184)
- 核心转发：**:209 `osgiServlet.service(req, res)`**
- :264-265 `request.setAttribute("killbill.osgi.servletContext", servletContext)` / `("killbill.osgi.servletConfig", servletConfig)`

### 2.10 插件元信息 / 健康检查

- `jaxrs/PluginInfoResource.java` `@Path(PLUGINS_INFO_PATH)` :58，:84 `getPluginsInfo()`
- `util/nodes/DefaultKillbillNodesService.java:156` `pluginInfoApi.getPluginsInfo()` → 写 `node_infos` 表
- `profiles/killbill/modules/DefaultHealthcheckPluginRegistry.java` `implements OSGIServiceRegistration<Healthcheck>`，`KillbillServerModule:118` 绑定

---

## 3. killbill-plugin-api 的 SPI 全清单

### 3.0 结论：零 OSGi 依赖，可原封不动保留

```
grep -rn "org\.osgi\|OSGI" killbill-plugin-api/  →  0 hits
```

所有子模块 pom 只依赖 `killbill-api` + `joda-time`（notification 额外 `jackson-annotations`）。

### 3.1 catalog
- **`CatalogPluginApi`**
  - `DateTime getLatestCatalogVersion(Iterable<PluginProperty>, TenantContext)`
  - `VersionedPluginCatalog getVersionedPluginCatalog(Iterable<PluginProperty>, TenantContext)`

### 3.2 control
- **`PaymentControlPluginApi`**
  - `PriorPaymentControlResult priorCall(PaymentControlContext, Iterable<PluginProperty>) throws PaymentControlApiException`
  - `OnSuccessPaymentControlResult onSuccessCall(...)` / `OnFailurePaymentControlResult onFailureCall(...)`

### 3.3 currency
- **`CurrencyPluginApi`** —— `getBaseCurrencies()` / `getLatestConversionDate(Currency)` / `getConversionDates(Currency)` / `getCurrentRates(Currency)` / `getRates(Currency, DateTime)`

### 3.4 entitlement
- **`EntitlementPluginApi`** —— `priorCall` / `onSuccessCall` / `onFailureCall`，`throws EntitlementPluginApiException`

### 3.5 invoice
- **`InvoicePluginApi`**（5 个方法，**都不抛受检异常**，与 entitlement/control 不同）
  - `priorCall` / `getAdditionalInvoiceItems` / `getInvoiceGrouping` / `onSuccessCall` / `onFailureCall`
- **`InvoiceFormatterFactory`**（第二个 SPI）
  - `createInvoiceFormatter(String defaultLocale, String catalogBundlePath, Invoice, Locale, CurrencyConversionApi, ResourceBundle, ResourceBundle, TenantContext)` + 一个 `default` 重载

### 3.6 notification
- **`NotificationPluginApi`** —— `void onEvent(ExtBusEvent killbillEvent)`
- 事件模型：`ExtBusEvent`, `ExtBusEventType`, `BlockingStateMetadata`, `BroadcastMetadata`, `InvoiceNotificationMetadata`, `InvoicePaymentMetadata`, `PaymentMetadata`, `SubscriptionMetadata`, `TagMetadata`, `TenantConfigMetadata`

### 3.7 payment
- **`PaymentPluginApi`**（18 个方法，最大的 SPI，全部 `throws PaymentPluginApiException`）
  - 交易：`authorizePayment` / `capturePayment` / `purchasePayment` / `creditPayment` / `refundPayment` / `voidPayment`
  - 查询：`getPaymentInfo` / `searchPayments`
  - 支付方式：`addPaymentMethod` / `deletePaymentMethod` / `getPaymentMethodDetail` / `setDefaultPaymentMethod` / `getPaymentMethods` / `searchPaymentMethods` / `resetPaymentMethods`
  - 其他：`buildFormDescriptor` / `processNotification`

### 3.8 usage
- **`UsagePluginApi`** —— `getUsageForAccount(...)` / `getUsageForSubscription(...)`

### 3.9 SPI 总数

**9 个可注册 SPI**：CatalogPluginApi / PaymentControlPluginApi / CurrencyPluginApi / EntitlementPluginApi / InvoicePluginApi / InvoiceFormatterFactory / NotificationPluginApi / PaymentPluginApi / UsagePluginApi
\+ 平台侧 3 个（`Healthcheck` / `ServiceDiscoveryRegistry` / `Servlet`）
\+ 1 个 single-registration（`MetricRegistry`）

> 其中 **`NotificationPluginApi` 不走 registry**，共 **8 个 `OSGIServiceRegistration<T>` + 1 个 `OSGISingleServiceRegistration<T>`** 需要在新运行时重建。

---

## 4. pom 层面的依赖

| 模块 | `killbill-platform-osgi-api` | `killbill-platform-osgi`（实现） | 备注 |
|---|:---:|:---:|---|
| `catalog` | ✅ :84 | — | |
| `currency` | ✅ :82 | — | |
| `entitlement` | ✅ :122 | — | |
| `invoice` | ✅ :137 | — | |
| `payment` | ✅ :134 | — | |
| `usage` | ✅ :85 | — | |
| **`util`** | ✅ :214 | **✅ :210（compile）** | 因为 `DefaultNodeInfo` 用了 `DefaultPluginsInfoApi.DefaultPluginInfo` |
| **`beatrix`** | ✅ :212 | **✅ :208** | 集成测试需要真跑 OSGi |
| **`profiles/killbill`** | ✅ :382 | **✅ :378** + `osgi-bundles-lib-killbill`(runtime, :386) | war 装配 |
| `jaxrs` | — | — | 只依赖 `killbill-platform-server`(classifier=classes) |
| 其余 7 个 | — | — | 只依赖 `killbill-platform-api` |

> **只有 3 个模块（`util`、`beatrix`、`profiles/killbill`）依赖 osgi 实现模块**；其余 6 个业务模块只依赖纯接口的 `osgi-api`。

---

## 5. profiles 的装配链

```
web.xml → KillbillGuiceListener  (profiles/killbill/.../listeners/)
  :64  extends KillbillPlatformGuiceListener
  :76  getServletModule()  ← jaxrsUriPattern 显式包含 PLUGINS_PATH（"/plugins"）
  :140 getModule(ServletContext) { return new KillbillServerModule(...); }
        ↓
KillbillServerModule  (profiles/killbill/.../modules/)
  :99  extends KillbillPlatformModule
  :112 installKillbillModules()   （安装各业务 Module）
  :118 bind(OSGIServiceRegistration<Healthcheck>).to(DefaultHealthcheckPluginRegistry.class)
        ↓
KillbillPlatformModule  (killbill-platform/server/)
  :101 configureOSGI();
  :219 install(new DefaultOSGIModule(configSource, ..., osgiDataSourceConfig, osgiEmbeddedDB));
        ↓
DefaultOSGIModule  (killbill-platform/osgi/glue/)
  :61  OSGI_NAMED = "osgi"
  :84  installOSGIServlet()  → bind HttpServlet @Named("osgi") → OSGIServlet   ← PluginResource 注入的就是它
  :99  installOSGIComponents()
```

### 运行时配置

`profiles/killbill/src/main/resources/killbill-server.properties`：
```
:65  org.killbill.billing.osgi.dao.url=jdbc:mysql://127.0.0.1:3306/killbill
:66  org.killbill.billing.osgi.dao.user=root
:67  org.killbill.billing.osgi.dao.password=root
:73  #org.killbill.billing.osgi.bundles.jruby.conf.dir=your_path   ← 已失效
```

---

## 6. 改造接触点估算

### 6.1 量级：主工程 main 约 46 文件 / 120 余处；加测试共 78 文件 / 190 余处

### 6.2 A. 机械替换类（改 import 即可，约 35 文件 / 100 处）

| 分类 | 文件数 | 说明 |
|---|:---:|---|
| 8 个 registry 实现类（`Default*ProviderPluginRegistry`） | 8 | payment×2, catalog, currency, entitlement, usage, invoice×2 |
| 8 个 Guice Provider | 8 | 逻辑都是 `new XxxRegistry()`，除 payment 两个要预注册内建插件 |
| 6 个 Module 的 `bind(TypeLiteral<OSGIServiceRegistration<T>>)` | 6 | PaymentModule:151-152, CatalogModule:98, CurrencyModule:46, DefaultEntitlementModule:54, UsageModule:54, DefaultInvoiceModule:135/139 |
| 持有 registry 的业务类 | ~11 | 见 §2 各域入口类 |
| 内建插件自注册（匿名 `OSGIServiceDescriptor`） | 3 | `DefaultPaymentProviderPluginRegistryProvider:45`, `DefaultPaymentControlProviderPluginRegistryProvider:47/66`, `NoOpInvoiceProviderPluginProvider:45` |

### 6.3 B. 需要真改实现（8 处，这是实际工作量）

| # | 位置 | 要做什么 |
|---|---|---|
| 1 | `jaxrs/PluginResource.java` :93/:96/:209/:264-265 | `@Named("osgi") HttpServlet` → 新运行时的 HTTP 路由 servlet；两个 `killbill.osgi.*` request attribute 约定要重定义 |
| 2 | `KillbillPlatformModule.configureOSGI()`（platform 侧） | `DefaultOSGIModule` → `PluginRuntimeModule` |
| 3 | `KillbillActivator.java`（:124-183 的 11 个 `@Inject` + :238-280 的 `serviceChanged`） | 整个「OSGi ServiceEvent → registerService」桥换成新 loader 的注册回调 |
| 4 | `util/nodes/DefaultNodeInfo.java` :24-27 | 依赖 `DefaultPluginsInfoApi.DefaultPluginInfo/DefaultPluginServiceInfo`（实现类），需提供等价实现或改用接口 |
| 5 | `util/pom.xml` :210 对 `killbill-platform-osgi` 的 compile 依赖 | 因 #4 而存在，解决 #4 后可去掉 |
| 6 | Notification 事件链 | 为 `NotificationPluginApi` 设计新分发机制（建议统一成 registry 模式） |
| 7 | `PluginsInfoApi` / `PluginInfo` / `PluginState` / `PluginStateChange` + `DefaultPluginsInfoApi.notifyOfStateChanged` | 插件生命周期语义重实现；影响 `PluginInfoResource`、`NodesInfoResource`、`DefaultKillbillNodesService` |
| 8 | `util/glue/KillbillApiAopModule.java` :25/:126 `instanceof ROTenantContext` | `ROTenantContext` 在 `osgi-api`，需迁到新 api 包 |

### 6.4 C. 可直接删除（3-4 处）

- `addRegistrationListener(Runnable)` —— main 源码 0 引用
- `OSGISingleServiceRegistration<T>` —— main 源码 0 引用
- `killbill/api/src/main/java/org/killbill/billing/osgi/api/LiveTrackerException.java` —— **全仓库 0 引用，死代码**
- `ContextClassLoaderHelper` 的 TCCL 包装（**仅当**新运行时用同一 ClassLoader 时才可省；隔离方案下仍必需）

### 6.5 D. 测试改造（32 文件 / 约 90 处）

| 文件 | 处数 |
|---|---|
| `beatrix/src/test/.../TestWithFakeKPMPlugin.java` | **12** |
| `beatrix/src/test/.../BeatrixIntegrationModule.java`（:168-174 用 `OptionalBinder` 给 3 个 registry 打 null 默认值） | 4 |
| `beatrix` 各 `TestWithXxxPlugin`（Catalog/Entitlement/Invoice/Usage/TaxItems/HardenCatalog/PaymentWithControl，7 个文件） | 各 2 |
| `payment/src/test/`（7 个文件） | 各 1-2 |
| `invoice/src/test/`（2 个文件） | 各 1-2 |
| `profiles/killbill/src/test/`（8 个文件） | 各 1-2 |
| `util/src/test/`（2 个文件） | 各 1 |

### 6.6 汇总

| 阶段 | 接触点 | 难度 |
|---|---|---|
| A 机械替换 | ~100 处 / 35 文件 | 低（sed + 编译修） |
| B 真实现改造 | **8 个关键点** | **高**（其中 6/8 在 platform 侧而非主工程） |
| C 删除简化 | 3-4 处 | 低 |
| D 测试改造 | ~90 处 / 32 文件 | 中 |
| E pom/配置 | ~12 处 | 低 |
| **合计** | **约 200 处 / 78 文件** | |

---

## 7. 降低风险的迁移路径

由于 `OSGIServiceRegistration<T>` / `OSGIServiceDescriptor` 是**纯 POJO 接口、零 org.osgi 依赖、只有 6 个方法**，最省事的路径是：

> 在 `lpr-api` 中定义 `PluginServiceRegistry<T>` / `PluginServiceDescriptor`（原样语义，裁掉 `addRegistrationListener`）
> → A 类 100 处退化为纯 import 替换
> → 6 个业务模块（catalog/currency/entitlement/invoice/payment/usage）**只需改 pom 一行 + import 一行，业务逻辑零改动**
> → 真正的工作全部收敛到 B 类的 8 个点上
