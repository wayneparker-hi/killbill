# Lightweight Plugin Runtime（LPR）设计

> 这是**设计文档**，不是实施报告。设计与最终实现有出入的地方，文末「设计与实现的三处出入」
> 逐条说明。实施中遇到分歧以本文为准；已固化为不可违反约束的部分见
> [`../../CLAUDE.md`](../../CLAUDE.md)。

---

## 一、它解决什么问题

LPR 是运行在单 JVM 内、面向 Java 平台的动态插件运行时。要解决的不是

> 如何让 Java 应用加载一个 jar？

而是

> 如何让一个长期演进的 Platform Core，**在不修改核心代码的前提下**，
> 动态发现、加载、运行、管理和替换第三方扩展模块。

能力范围：插件发现、加载、ClassLoader 隔离、生命周期、服务注册、事件订阅、
配置、版本管理、健康检查、管理接口。

### 明确不做

```
OSGi 完整规范          JVM Security Sandbox
Package Wiring         跨进程隔离
Bundle Fragment        独立进程部署
完整 Module Resolution  分布式 Service Discovery
                       容器级资源隔离
```

**LPR 是 Plugin Runtime，不是 OSGi Runtime。** 插件若是恶意代码，LPR 不保证 JVM 内安全隔离——
它防的是意外冲突，不是攻击。

---

## 二、四条设计原则

**1. API First。** 插件契约先于实现存在。API 里的每个类型都会成为所有插件的编译依赖，
进去了就再也改不掉。

**2. Runtime 与 Business Core 分离。** 运行时不知道什么是支付、什么是发票。
业务语义全部在 Core 一侧。

**3. Plugin 是独立模块，不是 Bean。** 它有自己的 ClassLoader、自己的依赖版本、
自己的生命周期。不能退化成"注册到容器里的一个对象"。

**4. 所有动态能力都必须有状态机。** 能被启动、停止、替换的东西，就必须有明确的状态与合法转换。
没有状态机的动态性会以竞态的形式还回来。

---

## 三、自研 vs 复用的边界（v2 的核心论点）

> **复用"机制"，自己定义"插件架构语义"。**

不守住这条，最后会变成"Guava + SOFAArk + Spring + 一堆胶水代码"，
框架的核心模型反而被第三方组件绑架。

| 能力 | 取舍 | 理由 |
|---|---|---|
| Plugin API / SPI | **自研** | 框架的核心契约 |
| Plugin Descriptor | **自研** | 决定插件模型 |
| Plugin Registry | **自研** | 核心运行时语义 |
| Plugin Lifecycle | **自研** | 核心状态机 |
| Service Registry | **自研** | Plugin ↔ Core 的关键边界 |
| Plugin Manager | **自研** | 整体编排 |
| ClassLoader 隔离 | 复用成熟实现，**但藏在自己的 SPI 之后** | 高风险、易踩坑 |
| EventBus | 可复用，**但不暴露第三方 API** | 机制成熟，契约要自己掌控 |
| YAML / SemVer / Metrics / Logging | 直接复用 | 没必要自己造 |
| HTTP / 调度 / 持久化 | 插件自己决定 | 运行时不该强绑定 |

### 为什么 ClassLoader 值得复用，而 Service Registry 不值得？

**判据是：这块能力的复杂度来自通用问题，还是来自我们自己的语义？**

ClassLoader 隔离的坑（双亲委派边界、资源查找、TCCL、卸载时的引用泄漏）是**所有人共有的**，
成熟实现踩过一遍了。

Service Registry 的复杂度**全部来自我们自己的语义**——什么时候注册、按什么作用域撤销、
升级时新旧版本如何共存。复用别人的注册表等于接受别人的语义，而那正是我们要定义的东西。

### Port & Adapter：复用必须可替换

复用不等于依赖。任何第三方能力都先在 `lpr-spi` 定义 port，再由独立 adapter 模块实现：

```
lpr-api    EventBus                  → lpr-event-*          ← 插件契约的一部分
lpr-spi    PluginClassLoaderFactory  → lpr-classloader-*    ← 运行时内部扩展点
           DescriptorParser          → lpr-descriptor-*
```

**port 分两层，取决于谁调用它**：插件自己调的（`EventBus`）在 lpr-api，
只有运行时调的（ClassLoader 工厂、描述符解析）在 lpr-spi。

**`lpr-core` 只依赖 port，不依赖任何具体实现。** 这不是洁癖：一旦第三方类型进入插件契约，
以后就换不掉了。`PluginContext.eventBus()` 返回的必须是自己定义的 `EventBus`，
不能是 `com.google.common.eventbus.EventBus`。

模块结构：

```
lpr/
├── lpr-api                 插件契约：Plugin / PluginContext / ServiceRegistry / ...
├── lpr-spi                 port：ClassLoader 工厂、描述符解析
├── lpr-core                注册表、生命周期、仓库——只有模型，没有实现
├── lpr-classloader-*       隔离后端（adapter）
├── lpr-descriptor-*        描述符解析（adapter）
├── lpr-event-*             事件总线实现（adapter）
├── lpr-management          安装 / 卸载
└── lpr-testkit             隔离性与泄漏性测试工具
```

### 判据不是"有没有第三方依赖"

容易想当然的一条推论是"自研的、零第三方依赖的实现可以留在 core"。**这条是错的**——
`lpr-classloader-default` 和 `lpr-event-default` 都是自研、零第三方依赖，依然在各自的模块里。

真正的判据是：**它是模型，还是基础设施？**

`lpr-core` 拥有插件模型（注册表、生命周期、仓库）。ClassLoader 怎么实现、事件怎么分发、
描述符是什么格式，都是可替换的基础设施——**实现留在 core 里，就迟早会被具体地接线**。

这不是假设。EventBus 的实现最初就放在 `lpr-core`，结果
`DefaultPluginLifecycleManager` 接收的是 `DefaultEventBus` 具体类而不是 `EventBus` 接口——
它本可以用接口的，只是因为实现就在手边。**接口存在不等于被使用。**

判据现在由 `TestArchitecturalConstraints.testRuntimeShipsNoInfrastructureImplementation` 保证：
lpr-core 的 classpath 上出现任何一个 adapter 的实现类即失败。

---

## 四、核心抽象

四个类型撑起整个模型：

```java
public interface Plugin {
    void start(PluginContext context) throws Exception;
    void stop() throws Exception;
}
```

`PluginContext` 是插件**唯一**能触及的东西：

| 方法 | 给什么 |
|---|---|
| `pluginId()` / `version()` | 自己的身份 |
| `config()` | 自己的配置 |
| `pluginRoot()` / `jarPath()` | 自己的安装位置 |
| `services()` | 注册自己提供的服务、查找别人提供的 |
| `eventBus()` | 进程内发布订阅 |
| `resources()` | 托管一切需要释放的东西 |
| `getPlatformService(Class)` | 平台能力 |

**刻意不给的**：插件管理器、插件注册表、生命周期管理器、以及插件自己的 ClassLoader。
插件扩展平台，不能反过来操纵托管它的运行时。

### 描述符

```yaml
id: my-plugin           # [a-z][a-z0-9._-]*，身份而非展示名
name: My Plugin
version: 1.2.0          # SemVer
entrypoint:
  class: com.acme.MyPlugin
config:
  my.key: "value"
```

**入口类是声明的，不是扫描出来的。** OSGi 靠扫描 jar 里的继承关系推断入口，
推断失败时报错位置离原因很远。写下来的东西是可 grep、可 review 的。

---

## 五、生命周期

```
INSTALLED → RESOLVED → STARTING → ACTIVE → STOPPING → STOPPED → UNINSTALLED
                           │                    │
                           └──────→ FAILED ←────┘
```

状态转换**集中管理**，插件不能自己改状态。并发下用 `AtomicReference` + CAS，
保证 START 不会被并发执行两次。

### 停止顺序是整个设计里最容易写错的地方

```
1. 撤销服务注册    ← 先切断新调用者
2. Plugin.stop()   ← 让它完成自己的收尾
3. 释放资源        ← 线程、订阅、连接
4. 关闭 ClassLoader ← 此时已无代码在运行
5. 丢弃引用        ← 否则什么都不会被卸载
```

顺序错了就会把干净的关闭变成崩溃：ClassLoader 关闭时还有请求在插件内部，
调用方会从一毫秒前还在正常工作的代码里拿到 `NoClassDefFoundError`。

### 资源必须"释放引用"，不能只"关闭"

`ResourceRegistry` 的契约是**丢弃引用**，不是调一下 `shutdown()`。

原因是 JDK 的真实行为（实测，非推测）：**已终止的 `Thread` 对象仍然强引用它的 `Runnable`**，
因而持有 lambda 捕获的一切。旧版 JDK 的 `Thread.exit()` 会把 `target` 置空，
虚拟线程重构后不再这样做。

后果：一个 `shutdown()` 过但仍被字段引用的线程池，会通过 worker 线程的 task 继续钉住
插件 ClassLoader——插件"卸载"了，类一个都没回收，反复热更新就是 metaspace 泄漏。

---

## 六、ClassLoader 隔离

> **API parent-first + 插件依赖 child-first**

```
loadClass(name)
    ├── JDK 类           → Parent
    ├── 插件 API         → Parent
    ├── 平台 API         → Parent
    └── 其他             → 插件自己的 classpath
```

这样插件可以带自己的 Jackson、自己的 HTTP 客户端、自己的 SDK，
而 `PaymentPluginApi.class` 在插件侧与 Core 是**同一个 Class 对象**。

### 为什么不能全部 child-first？

因为跨边界传递的类型必须同一。Core 的 `PaymentPluginApi.class` 与插件的
`PaymentPluginApi.class` 若不是同一个 Class 对象，运行时就是 `ClassCastException`——
而且两个类名完全一样，报错极难读懂。

**推论一**：平台侧的插件 API 绝不能放在插件惯用的包名下，否则会被判成 child-first 而失去共享性。

**推论二**（同样重要）：parent-first 名单只列包前缀，但**任何跨边界传递的第三方类型**
都受同一约束。它们不在名单里，只能靠插件把该依赖声明为 `provided` 来达到同样效果。

判断方法：*这个类型会出现在我注册的服务的方法签名里，或者我会把它传给平台/别的插件吗？*

---

## 七、Service Registry

Plugin ↔ Core 的边界。插件注册实现，Core 按类型和选择器查找——
**业务层只表达需求，不指定具体插件**。

### 注册作用域必须是 `(pluginId, version)`，不能只按 pluginId

因为无空窗升级的实现方式是**新旧两个版本同时加载且共享同一个 pluginId**。
若按 pluginId 撤销注册，停掉旧版本会把新版本刚注册的服务一并撤掉——
插件显示 ACTIVE，却什么都不提供。

### 服务生命周期严格绑定插件状态

STARTING 时注册，STOPPING 时按上面的顺序撤销。不允许插件停止后还留着可被查到的服务。

---

## 八、版本管理与热更新

磁盘布局：

```
plugins/<plugin-id>/
├── ACTIVE          # 元数据：active=1.3.0, previous=1.2.0
├── DISABLED        # 存在则整个插件不加载
├── 1.2.0/
│   ├── plugin.jar
│   └── plugin.yaml
└── 1.3.0/
```

版本用 **SemVer 比较**，不是字符串比较（字符串比较会让 `"10.0" < "9.0"`）。

### 为什么不能 Stop → Load → Start？

那中间有一段时间**没有任何提供者**。对一个正在处理支付的系统，这段空窗是不可接受的。

正确顺序：

```
1. 加载新版本（旧版本仍在服务）
2. 启动新版本
3. 原子切换服务指向
4. 停止旧版本
```

**安装 ≠ 激活 ≠ 启动**。三步分开，才有回滚的余地：先装 1.3.0、再切、发现不对再回滚。
`ACTIVE` 记着 `previous`，回滚就是切回去。

### 安装必须是原子的

文件先落到临时目录，下载与校验都成功后才移动就位。失败不能留下半个版本目录——
因为运行时是靠**列目录**发现插件的，写了一半的版本目录和真的版本目录长得一模一样。

---

## 九、测试体系

大部分测试是常规的。**两个不能少**，因为它们保护的性质无法靠 code review 发现：

**隔离性**：两个插件依赖同一个库的不同版本，各自加载各自的；但共享的 API 类型
在两侧与 Core 是同一个 Class 对象。

**泄漏性**：install → start → stop → 丢弃引用 → GC → `WeakReference<ClassLoader>.get() == null`。

第二个是判断"卸载是不是真的"的唯一手段。没有它，插件卸载可以完全是假的而没人发现。

> **插件必须从真 jar 加载，不能从测试 classpath。** classpath 上的类由应用 ClassLoader 加载，
> 测试会"轻松通过"却什么都没验证——真正会坏的正是经过插件 ClassLoader 的那条路径。

---

## 十、开发顺序：按风险排，不按模块排

```
Phase 1  ClassLoader 隔离      ← 硬门禁
Phase 2  Lifecycle
Phase 3  Service Registry
Phase 4  Resource Management
Phase 5  Event Bus
Phase 6  Version / Update
```

**Phase 1 不通过，技术路线就不成立，应该换方案而不是继续往上堆。**
不要先写一堆 Manager / Registry，回头才发现隔离方案不可行。

---

## 十一、设计与实现的三处出入

设计文档会过时。以下三处是实施中**基于证据推翻或修正**的，记在这里以免误导：

### 1. ClassLoader 默认后端：从 SOFAArk 改为自研

v2 建议"复用 SOFAArk"。动手前用 `javap` 看了 `sofa-ark-container` 的真实形状，发现
`ClassLoaderService` 的整个 API 围绕**插件声明式导出/导入包**构建
（`isClassInImport` / `getExportMode` / `findExportPlugin`）——
这与 OSGi 的 `Import-Package` / `Export-Package` **结构上是同一种契约**，
而干掉"包白名单成为插件 ABI"正是本次改造的动机之一。

对照真实需求：插件是 fat jar、不支持插件间依赖、只需要一份固定的 parent-first 白名单。
没有依赖图要解析，没有跨插件导出表要维护——而这两样正是模块系统体积的来源。

自研实现约 200 行，原本约 200 行的 system-packages 清单压缩成 13 个包前缀。

**SPI 边界仍然保留**：`PluginClassLoaderFactory` 这个 port 没有因为"只有一个实现"而取消。

### 2. EventBus 没有抽 port

v2 画的模块图里有 `lpr-event-guava` / `lpr-event-default`。实际只做了自研实现，
放在 `lpr-core` 里（几百行，零第三方依赖）。

理由就是第三节末尾那条：**没有第二个实现的 port 只是多一层间接。** Metrics 同理。

### 3. `org.killbill.billing.plugin.` 被挖回 child-first

v1 的委派模型说"平台 API → parent"。实施中发现平台的包前缀会把**插件自己的实现代码**
也判成 parent-first——因为插件惯用同一个包名。生产环境靠"平台找不到就回落"歪打正着，
但只要出现同名类就会静默拿到平台副本。

修正为：该前缀从 parent-first 里挖回 child-first，并因此要求
**平台侧的插件 API 绝不能放在这个包下**。

---

## 十二、一句话

> LPR 是一个**自己拥有插件模型、基础设施可替换**的插件运行时。
>
> 插件模型（契约、生命周期、注册表、隔离语义）自研，因为那是这个框架存在的理由；
> 机制（ClassLoader 实现、YAML 解析、指标、日志）复用，但一律藏在自己的 port 之后。
