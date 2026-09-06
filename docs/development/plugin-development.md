# 写一个 LPR 插件

> 这份文件回答四个问题：`plugin.yaml` 有哪些字段、插件能从平台拿到什么、
> `lpr-testkit` 怎么用、热更新与回滚怎么操作。
>
> 从 OSGi bundle **迁移**已有插件，看 [plugin-migration-guide.md](../migration/plugin-migration-guide.md)。
> 这份是**从零写新插件**的规范。

---

## 一、最小插件

一个插件 = 一个实现 `Plugin` 的类 + 一份 `plugin.yaml`。没有基类，没有注解，没有 bnd 指令。

```java
public class MyPlugin implements Plugin {

    @Override
    public void start(final PluginContext context) {
        context.services().register(PaymentPluginApi.class, new MyPaymentApi(context));
    }

    @Override
    public void stop() {
        // 通常是空的：注册和资源都由运行时回收
    }
}
```

```yaml
id: my-plugin
name: My Plugin
version: 1.0.0
entrypoint:
  class: org.killbill.billing.plugin.my.MyPlugin
```

pom 的关键是**依赖的作用域**，见第五节。

---

## 二、`plugin.yaml` 字段与校验规则

| 字段 | 必填 | 规则 | 违反时 |
|---|---|---|---|
| `id` | 是 | 匹配 `[a-z][a-z0-9._-]*` | 描述符解析失败，该版本被跳过 |
| `version` | 是 | SemVer（`MAJOR.MINOR.PATCH[-预发布]`） | 解析失败 |
| `entrypoint.class` | 是 | 全限定类名，必须实现 `Plugin` 且有公开无参构造器 | 启动时 `FAILED`，其余插件照常 |
| `name` | 否 | 展示用；缺省等于 `id` | — |
| `config` | 否 | `String → String` 的映射 | — |

**三条容易踩的**：

1. **`id` 是身份，不是展示名**。它决定磁盘目录、服务注册的默认名、
   以及节点命令里的 `pluginName`。改 `id` 等于换一个插件。
2. **版本用 SemVer 比较，不是字符串比较**。这是刻意修掉的既有缺陷——
   旧的 `DefaultPluginConfig.compareTo` 用 `String.compareTo`，于是 `"10.0" < "9.0"`。
3. **`entrypoint.class` 是声明的，不是扫描出来的**。OSGi 靠扫描 jar 里
   `KillbillActivatorBase` 的子类来推断入口，推断失败时的报错离原因很远。

### 校验发生在什么时候

- **描述符格式**：`INIT_PLUGIN` 阶段扫描目录时。坏描述符 = 这个版本不存在，不影响启动。
- **入口类**：`START_PLUGIN` 阶段实例化时。失败则该插件 `FAILED`，被列为 mandatory 时才中断启动。
- **服务注册名**：注册时。不匹配 `[a-z][a-z0-9._-]*` 会被拒绝并记 warn——
  **注册失败是静默的**，业务侧只会看到"查不到这个插件"。

---

## 三、插件能从平台拿到什么

### `PluginContext`

| 方法 | 给你什么 |
|---|---|
| `pluginId()` / `version()` | 自己的身份 |
| `config()` | `plugin.yaml` 里 `config:` 段的键值 |
| `pluginRoot()` / `jarPath()` | 自己的安装目录与 jar。**随 jar 发布的数据文件从这里读** |
| `services()` | 注册自己提供的服务、查找别的插件提供的服务 |
| `eventBus()` | 进程内发布订阅（尽力而为，无持久化） |
| `resources()` | 托管一切需要释放的东西 |
| `getPlatformService(Class)` | 平台能力，见下表 |

### `getPlatformService` 可取的类型

业务 API（等价于旧的 `OSGIKillbill` 门面，但按类型单取）：

```
AccountUserApi      AdminPaymentApi     AuditUserApi        CatalogUserApi
CurrencyConversionApi  CustomFieldUserApi  EntitlementApi   ExportUserApi
InvoicePaymentApi   InvoiceUserApi      KillbillNodesApi    OverdueApi
PaymentApi          PluginsInfoApi      RecordIdApi         SecurityApi
SubscriptionApi     TagUserApi          TenantUserApi       UsageUserApi
```

平台能力：`Clock`、`DataSource`、`KillbillConfigSource`、`MetricRegistry`。

**不是每个部署都发布全部**：`PlatformServices` 按绑定是否存在来发布，
所以纯支付 profile 里没有 `OverdueApi`。取不到会抛异常并列出可用清单——
**插件应该在 `start()` 里尽早取，让缺失立刻暴露，而不是等第一笔业务**。

要新增一个可发布的类型，改 `PlatformServices.PUBLISHED` 一处即可。

### 一条时序约束（会咬人）

`START_PLUGIN` 属于 `STARTUP_PRE` 序列，**插件在所有 Core service 的 `START_SERVICE` 之前就启动完了**。

所以 `start()` 里**可以拿到** API 引用，但**不能立刻调用它们做业务**——Core 还没就绪。
需要 Core 的工作要挂在 `.../lifecycle/STARTED` 事件上。

---

## 四、资源必须托管

```java
final ScheduledExecutorService pool = Executors.newScheduledThreadPool(2);
context.resources().manage(pool);          // AutoCloseable
context.resources().onClose(client::shutdown);  // 或者一个回调
```

**这不是洁癖**。JDK 21 上，已终止的 `Thread` 对象仍然强引用它的 `Runnable`，
因而持有 lambda 捕获的一切——包括插件的 ClassLoader。
一个 `shutdown()` 过但仍被字段引用的线程池，会让插件"卸载了但类一个都没回收"，
反复热更新就是 metaspace 泄漏。

判据：`lpr-testkit` 的泄漏性测试会挂。

---

## 五、pom 的作用域规则

这是新插件最容易配错、且**错了要到运行时才报 `ClassCastException`** 的地方。

| 依赖 | 作用域 | 为什么 |
|---|---|---|
| `killbill-api`、`killbill-plugin-api-*` | `provided` | `org.killbill.*` 是 parent-first，打进 jar 里也用不上 |
| `killbill-lpr-api` | `provided` | 同上 |
| 任何**跨插件边界传递**的第三方类型（如 `com.codahale.metrics`） | `provided` | 两边必须是同一个 Class 对象 |
| 插件自己用的第三方库 | `compile` | child-first，各插件可以用不同版本，这正是隔离的价值 |

**判断方法**：这个类型会出现在我注册的服务的方法签名里，或者我会把它传给平台/别的插件吗？
会 → `provided`；不会 → `compile`。

> 反例：`metrics-plugin` 最初把 `metrics-core` 声明成 `compile`，
> 于是它 `new` 出来的 codahale registry 和平台侧 `KillBillCodahaleMetricRegistry`
> （parent-first 加载）期望的类型不是同一个 Class，构造时就 `ClassCastException`。

### 包名也会影响 ClassLoader 行为

`org.killbill.billing.plugin.` 被从 parent-first 里**挖回 child-first**——
插件实现代码惯用这个包。**平台侧的插件 API 绝不能放在这个包下**。

---

## 六、`lpr-testkit` 怎么用

三个类，对应三种测试。

### `PluginJarBuilder`：把插件打成真 jar

```java
new PluginJarBuilder().build(Map.of("com.acme.MyPlugin", SOURCE), versionDir.resolve("plugin.jar"));
// 或者从已编译的 target/classes 打包
new PluginJarBuilder().packageDirectory(Path.of("target/classes"), jarPath);
```

**为什么要打 jar 而不是直接用 classpath 上的类**：classpath 上的类由应用 ClassLoader 加载，
测试会"轻松通过"却什么都没验证——真正会坏的是经过插件 ClassLoader 的那条路径。

### `PluginFixture`：声明一个测试用插件

链式构造，源码即插件——不需要单独建一个测试模块。

```java
final PluginFixture fixture = PluginFixture.plugin("my-plugin", "1.0.0")
        .entrypoint("com.acme.MyPlugin", MY_PLUGIN_SOURCE)
        .withClass("com.acme.MyService", MY_SERVICE_SOURCE)   // 可选，附加类
        .withConfig("my.greeting", "hi")                       // 写进 plugin.yaml 的 config 段
        .named("My Plugin");                                   // 可选，展示名
```

### `PluginTestRuntime`：起一个最小运行时

```java
try (PluginTestRuntime runtime = PluginTestRuntime.create()) {
    runtime.install(fixture);          // 编译成 jar + 写 plugin.yaml + 按仓库布局落盘
    runtime.discover();
    runtime.startAll();

    assertEquals(runtime.state("my-plugin").orElseThrow(), PluginState.ACTIVE);
    final PaymentPluginApi api = runtime.service(PaymentPluginApi.class, "my-plugin");
}
```

需要平台服务的插件，用另一个工厂方法注入：

```java
PluginTestRuntime.create(Map.of(Clock.class, new DefaultClock()));
```

`manager()` / `services()` / `eventBus()` / `repository()` 暴露底层组件，
用于验证升级、回滚、`disableVersion` / `setActiveVersion` 这类操作。

### 两个必须有的测试

写新插件时至少覆盖：

1. **它注册的服务能被查到**，且名字就是文档里承诺的那个。
2. **停止后 ClassLoader 能被 GC**——如果插件起了线程或连接，这条会抓到没托管的资源。

---

## 七、热更新与回滚

磁盘布局：

```
plugins/my-plugin/
├── ACTIVE          # properties 文件：active=1.3.0, previous=1.2.0
├── DISABLED        # 存在则整个插件不加载
├── 1.2.0/
│   ├── plugin.jar
│   └── plugin.yaml
└── 1.3.0/
```

### 操作方式：节点命令

**唯一的管理面是 Kill Bill 既有的 `/1.0/kb/nodesInfo`**，不是另一套 REST。
命令广播到集群里每个节点，由 `PluginNodeCommandListener` 执行。

| 命令 | 效果 |
|---|---|
| `INSTALL_PLUGIN` | 按 `uri` 属性取 jar，校验 `sha1`，落盘成新版本。**不启动** |
| `START_PLUGIN` | 启动；带 `pluginVersion` 时先切换 ACTIVE 再启动 |
| `STOP_PLUGIN` | 停止（不删文件） |
| `RESTART_PLUGIN` | 停止再启动，带版本时即为升级 |
| `UNINSTALL_PLUGIN` | 先停止，再删除该版本目录 |

`INSTALL_PLUGIN` 支持的 `properties`：

| key | 说明 |
|---|---|
| `uri` | **必填**。`file:` 或 `http(s):`。Maven 坐标可用 `ArtifactSource.ofMavenCoordinates` 换算 |
| `sha1` | 可选，校验不过则整个安装回滚 |
| `descriptor` | 可选，覆盖默认写入的 `plugin.yaml` |

### 无空窗升级怎么实现的

升级时新旧两个版本**同时加载且共享同一个 pluginId**。
所以服务注册按 `(pluginId, version)` 作用域撤销——
若只按 pluginId 撤销，停旧版本会把新版本刚注册的服务一并撤掉，
插件显示 ACTIVE 却什么都不提供。

判据：`lpr-testkit` 的 `TestPluginRuntimeEndToEnd.testUpgradingLeavesNoWindowWithoutAProvider`
与 `testRollbackReturnsToThePreviousVersion`。

### 回滚

`ACTIVE` 文件记着 `previous`，`PluginManager.rollback(pluginId)` 切回去。
**这也是为什么安装不等于启动**：先装 1.3.0、再切、发现不对再回滚，
三步分开才有回滚的余地。

### 安装的原子性

文件先落到同目录下的临时目录，下载与校验都成功后才 `ATOMIC_MOVE` 就位。
失败不会留下半个版本目录——这很重要，因为运行时是靠**列目录**发现插件的，
写了一半的版本目录和真的版本目录长得一模一样。

---

## 八、检查清单

写完一个插件，逐条过：

- [ ] `plugin.yaml` 的 `id` 匹配 `[a-z][a-z0-9._-]*`，`version` 是 SemVer
- [ ] `entrypoint.class` 有公开无参构造器
- [ ] `killbill-*` 和跨边界的第三方依赖都是 `provided`
- [ ] 每个 executor / connection / 订阅都进了 `resources()`
- [ ] `start()` 里没有立刻调用 Core API 做业务
- [ ] 有一个测试**从真 jar 加载**并验证服务可查到
- [ ] 有一个泄漏性测试
