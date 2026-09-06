# 插件迁移指南：从 OSGi Bundle 到 LPR 插件

> 参考实现：[`plugins/hello-world-plugin`](../../plugins/hello-world-plugin)，从官方
> `killbill-hello-world-java-plugin` 迁移而来。
> 端到端验证：`TestHelloWorldPluginMigration`（发现 → 启动 → 进入 Kill Bill 支付注册表 → 支付往返 → 停止移除）。

---

## 一句话概括

**插件的业务代码一行不用改，改的只是入口和打包。**

`PaymentPluginApi`、`InvoicePluginApi` 这些 SPI 从来就不依赖 OSGi——它们在 `killbill-plugin-api`
里是纯 Java 接口。依赖 OSGi 的只有 Activator 和 pom 里的 bnd 指令。

---

## 迁移对照表

| | OSGi Bundle | LPR 插件 |
|---|---|---|
| **入口** | `extends KillbillActivatorBase`，由 bnd 扫描字节码找出 | `implements Plugin`，在 `plugin.yaml` 里显式声明 |
| **拿 Core 服务** | 每个服务开一个 `ServiceTracker` | `PluginContext.getPlatformService(Type.class)` |
| **注册服务** | 建 `Hashtable` 塞 `killbill.pluginName`，再 `registrar.registerService(context, Type.class, impl, props)` | `context.services().register(Type.class, impl)` |
| **配置** | `configProperties.getProperties()` | `context.config().get(key)` / `find(key, Integer.class)` |
| **事件** | `dispatcher.registerEventHandlers(...)` | `context.eventBus().subscribe(Type.class, handler)` |
| **资源清理** | 自己在 `stop()` 里逐个关 | `context.resources().manage(...)`，运行时统一释放 |
| **packaging** | `bundle`（maven-bundle-plugin + bnd） | `jar` |
| **包可见性** | pom 里 40 多行 `Import-Package` | 无需声明（运行时的 parent-first 前缀表统一处理） |
| **依赖** | `Embed-Dependency: *;inline=true` 全部 inline | 照常 `compile` 依赖；API 用 `provided` |

---

## 分五步做

### 1. 换入口类

```java
// 之前：HelloWorldActivator extends KillbillActivatorBase，约 120 行
public class HelloWorldPlugin implements Plugin {

    @Override
    public void start(final PluginContext context) {
        final String greeting = context.config().find("helloworld.greeting").orElse("Hello, world");

        context.services().register(PaymentPluginApi.class,
                                    new HelloWorldPaymentPluginApi(greeting),
                                    Map.of(PluginServiceProperties.REGISTRATION_NAME, "hello-world-plugin"));

        context.resources().manage(
                context.eventBus().subscribe(Object.class, event -> log.debug("Observed {}", event)));
    }

    @Override
    public void stop() {
        // 服务与订阅由运行时撤销，这里只处理插件自己管的状态
    }
}
```

**两条必须遵守的**：

- **`start()` 里不要调 Kill Bill 的业务 API。** 插件在 `STARTUP_PRE` 阶段启动，早于所有 Core service
  的 `START_SERVICE`。这些 API 已注入但尚未开始服务。需要 Core 的活儿放到事件处理里。
  （这是 Kill Bill 既有语义，LPR 原样复刻，见 CLAUDE.md 约束 A2）
- **任何持有线程/连接/回调的东西都交给 `context.resources()`。** 不托管的资源会钉住插件的
  ClassLoader，让"卸载插件"变成空操作（约束 A4）。

### 2. 写 `plugin.yaml`

放在 `src/main/resources/plugin.yaml`：

```yaml
id: hello-world
name: Hello World Plugin
version: 1.0.0
entrypoint:
  class: org.killbill.billing.plugin.helloworld.HelloWorldPlugin
config:
  helloworld.greeting: "Hello from LPR"
```

- `id` 必须匹配 `[a-z0-9][a-z0-9.-]*`，且**等于插件安装目录名**
- `version` 必须是 SemVer，且**等于版本目录名**（运行时靠数值比较决定启动哪个版本）
- `entrypoint.class` 显式写出，不再靠扫描推断

### 3. 注册名：注意兼容存量数据

插件的**身份**（`id`）和 Kill Bill **查找它用的名字**（registration name）可以不同。默认后者等于前者，
但存量插件常常不一致——比如 `hello-world` 插件在支付方法表里存的是 `hello-world-plugin`。

```java
context.services().register(PaymentPluginApi.class, impl,
        Map.of(PluginServiceProperties.REGISTRATION_NAME, "hello-world-plugin"));
```

**迁移时务必核对**：`payment_methods.plugin_name` 里存的是什么，注册名就得是什么，否则历史支付方法会解析不到插件。

### 4. 改 pom

```xml
<packaging>jar</packaging>   <!-- 不再是 bundle -->
```

删掉全部 `osgi.*` 属性和 `maven-bundle-plugin` 配置。

**API 依赖改成 `provided`**：

```xml
<dependency>
    <groupId>org.kill-bill.billing</groupId>
    <artifactId>killbill-lpr-api</artifactId>
    <scope>provided</scope>
</dependency>
```

运行时提供这些类型，且它们必须 parent-first 解析，好让插件和 Core 共享同一个 `Class` 对象。
把它们打进插件 jar 正是 `ClassLoaderPolicy` 要防的错误——不过即使打进去了，运行时也会让平台的副本胜出
（见 `TestClassLoaderIsolation.testPluginCannotShadowApiTypes`）。

插件自己的第三方依赖保持 `compile` 即可。不需要 shade：运行时的 ClassLoader 隔离让每个插件可以带自己的版本。

### 5. 部署布局

```
plugins/
└── hello-world/                 # 目录名 = plugin.yaml 的 id
    ├── ACTIVE                   # 可选，properties 格式：active=1.0.0
    └── 1.0.0/                   # 目录名 = plugin.yaml 的 version
        ├── plugin.jar
        ├── plugin.yaml
        └── lib/                 # 可选，额外 classpath 条目
```

与旧布局的三处差异及原因：

| 旧 | 新 | 为什么 |
|---|---|---|
| `SET_DEFAULT` 符号链接 | `ACTIVE` 文件（`active=1.0.0`） | 符号链接在打包、复制、容器镜像层之间行为不一致 |
| 版本按字符串排序 | 按 SemVer 数值比较 | 旧的排序让 `10.0` 排在 `9.0` 之前，双位数版本会启错 |
| 目录里第一个 `.jar` | 优先 `plugin.jar`，或目录内唯一的 jar，歧义则报错 | 旧的依赖目录遍历顺序，不确定 |

---

## 包名会影响 ClassLoader 行为

运行时对 `org.killbill.` 走 parent-first（那是平台的 API），但把
**`org.killbill.billing.plugin.`** 挖了回来走 child-first。

这正好覆盖插件的常见包名：

| 包 | 归属 | 解析方向 |
|---|---|---|
| `org.killbill.billing.payment.plugin.api.*` | 平台 SPI | parent-first |
| `org.killbill.billing.catalog.api.*` | 平台 API | parent-first |
| `org.killbill.billing.plugin.helloworld.*` | **插件自己的代码** | child-first |
| `org.killbill.billing.plugin.api.*` / `.plugin.core.*` | 插件基类库（插件自带） | child-first |
| `com.stripe.*` 等第三方 | 插件自带 | child-first |

> 这条例外规则是被 `TestHelloWorldPluginMigration` 抓出来的：一开始 `org.killbill.` 整体
> parent-first，导致插件自己的类被判给平台去加载。生产环境下靠"平台找不到就回落"歪打正着，
> 但只要出现同名类就会静默拿到平台的副本。

包名在这两个范围之外的插件（比如 `com.mycompany.billing.plugin`）自动就是 child-first，不用管。

---

## 验证迁移结果

`lpr-testkit` 提供了一个真实运行时，用它跑插件——**从 jar 加载，不是从测试 classpath**：

```java
new PluginJarBuilder().packageDirectory(Path.of("target", "classes"),
                                        versionDir.resolve("plugin.jar"));
Files.copy(Path.of("src", "main", "resources", "plugin.yaml"), versionDir.resolve("plugin.yaml"));
```

这个区别很关键：在 classpath 上，插件的类由 AppClassLoader 加载，什么都能跑通；打进 jar 才会走插件
ClassLoader，也才可能暴露真问题。

完整示例见
[`TestHelloWorldPluginMigration`](../../plugins/hello-world-plugin/src/test/java/org/killbill/billing/plugin/helloworld/TestHelloWorldPluginMigration.java)，
它断言了整条链路：

1. 插件被发现、启动，状态为 `ACTIVE`
2. 出现在 Kill Bill 真实的 `DefaultPaymentProviderPluginRegistry` 里，名字是支付方法表里存的那个
3. 一笔 `purchasePayment` 打到插件，返回真实的 `PaymentTransactionInfoPlugin`
4. `plugin.yaml` 里的配置确实到达了插件
5. 插件的类由插件 ClassLoader 加载，但 `PaymentPluginApi` 与 Core 是同一个 `Class` 对象
6. 停止后从注册表消失

---

## 迁移检查清单

- [ ] Activator 换成 `implements Plugin`
- [ ] `start()` 里没有调用 Kill Bill 业务 API
- [ ] 所有线程 / 订阅 / 连接进了 `context.resources()`
- [ ] `plugin.yaml` 的 `id` / `version` 与目录名一致
- [ ] 注册名与 `payment_methods.plugin_name` 等存量数据一致
- [ ] `packaging` 改回 `jar`，删掉 `osgi.*` 属性与 `maven-bundle-plugin`
- [ ] API 依赖改成 `provided`
- [ ] 写了从 jar 加载的迁移测试并通过
