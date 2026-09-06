# 迁移方案：OSGi(Felix) → LPR 轻量级插件运行时

> 本文是经过评审确认的实施方案。分析依据见 [`../analysis/`](../analysis/)，设计依据见 [`../design/`](../design/)。

---

## Context

**为什么做这件事。** Kill Bill 用 Apache Felix (OSGi) 作插件运行时。OSGi 完整规范（Package Wiring / Bundle Fragment / Module Resolution）远超实际需要，代价是：

- `Import-Package` 白名单成为插件 ABI 契约（`killbill-oss-parent/pom.xml:152-223` 硬编码 40+ 包名）
- `OSGIConfig` 里两坨约 200 行的 system-packages 导出清单（占该文件 90%）
- 9 个模块必须 `packaging=bundle`
- 插件必须靠 bnd 扫描继承关系隐式生成 `Bundle-Activator`

目标是换成自研 **LPR（Lightweight Plugin Runtime）**：保留 ClassLoader 隔离、Service Registry、生命周期状态机这三个真正需要的能力，丢掉 OSGi 规范其余部分。

**最大的简化前提**：后续会重写全部官方插件实现，因此**不需要保留对现存 OSGi bundle 插件的任何兼容**。

**同时解决的工程问题。** 6 个核心仓库原本独立版本、独立 release。本次改造横跨其中 4 个，多仓库模式下每次改动都要走一遍官方 CI 那套 `versions:update-parent` + `versions:set-property` 版本同步舞蹈，不可持续。因此先合并成单仓库、单 reactor。

**期望结果**：一个 `mvn clean install` 全量构建通过的顶级工程，Felix/OSGi 彻底移除，插件由 LPR 加载，业务代码不再出现 `OSGi` 字眼。

---

## 一、为什么合并成单仓库是必要前提

1. **依赖图是干净的线性 DAG，无真环**：
   `oss-parent → killbill-api → killbill-plugin-api → killbill-commons → killbill-platform → killbill`
   唯一"反向边"是 oss-parent 的 `dependencyManagement` 反向引用全部 5 个子项目版本（约 100 条），靠属性延迟解析打破——`${killbill.version}` 在 oss-parent 里**只用不定义**，由 `killbill/pom.xml:64` 补上。单 reactor 里把这些属性收归 oss-parent 即可根除。

2. **官方自己就在"模拟单仓库"**：`killbill-oss-parent/.github/workflows/ci.yml` checkout 8 个仓库到同级目录，然后 `versions:update-parent` → build → `versions:set-property` 循环 6 遍。这套脚本合并后**整个可以删掉**。

3. **本地 Maven 仓库无任何 `org/kill-bill` 制品**：多仓库下改一行 `killbill-api` 就要 install 一遍才能被下游看到；单 reactor 一次构建全通。

### 一个需要校准的预期

选择"保留原目录以便合上游"与"一步到位重命名 `OSGIServiceRegistration`"这两个目标互斥——重命名 88 处 import + 删掉整个 osgi 模块后，上游 merge 已经报废。

"保留原目录"的真实收益是：
- 降低一次性迁移风险
- 保留代码归属可读性
- **`${project.parent.basedir}` 等路径引用不受影响**（killbill 的 16 个模块靠它指向 `spotbugs-exclude.xml`）

而不是长期同步能力。

---

## 二、已确认的决策

| 决策项 | 选择 |
|---|---|
| 仓库形态 | 聚合式：保留 6 个原目录 + 顶级 aggregator pom + 新增 `lpr/` |
| groupId / Java 包名 | 全部保留 `org.kill-bill.*`，业务包名不动 |
| ClassLoader 后端 | **自研为默认**，SOFAArk 作为可替换 adapter 保留（见文末附录：对 v2 的一处修正） |
| 外部依赖 | **全部 Port & Adapter，可扩展替换**（硬约束） |
| 核心业务调用面 | 一步到位重命名，彻底消除 `OSGi` 字眼 |
| KPM | **LPR 接管，删掉 KPM**（3756 行） |
| 插件布局/描述符 | 按 V1 文档重新定义（`plugin.yaml` + `ACTIVE` metadata） |
| 插件 SDK (`libs/killbill`) | 不做兼容层，直接重新设计为 `lpr-api` |

### Port & Adapter 是硬约束

`lpr-core` **不允许**直接依赖 SOFAArk / Guava / Micrometer / SnakeYAML 中任何一个：

```
lpr-spi                          ← 只有接口，零第三方依赖
  ├── PluginClassLoaderFactory   → lpr-classloader-default（默认，自研 URLClassLoader）
  │                              → lpr-classloader-sofaark（可替换性证明 + 备选）
  ├── EventBusProvider           → lpr-event-default（自研，几百行）
  ├── MetricsCollector           → lpr-metrics-micrometer
  └── DescriptorParser           → lpr-descriptor-snakeyaml
```

Adapter 由装配层（Guice）选择，`lpr-core` 只面向 SPI 编程。

> **架构违规判据**：任何时候 `lpr-core` 的 classpath 上出现 `com.alipay.sofa`、`com.google.guava`、`io.micrometer`、`org.yaml`，即为违规。
> 已由 `TestArchitecturalConstraints` 固化（跑在 `fast` 组）。**没用 maven-enforcer 是刻意的**：
> enforcer 经常被 `-Dcheck.skip-enforcer=true` 关掉，而"该生效时是关着的规则"不算规则。
> 该守卫已用"故意给 lpr-core 加 guava"验证过确实会失败。

---

## 三、目标目录结构

```
killbill/                          # 顶级工程
├── pom.xml                        # aggregator（packaging=pom，无 parent）
├── docs/                          # 分析与设计文档
├── killbill-oss-parent/           # 保留，改版本属性 + 删 OSGi 相关配置
├── killbill-api/                  # 保留，改 PluginConfigServiceApi 签名
├── killbill-plugin-api/           # 保留，零 OSGi 依赖，完全不动
├── killbill-commons/              # 保留，完全不动
├── lpr/                           # 新增：自研插件运行时
│   ├── lpr-api/                   # Plugin / PluginContext / ServiceRegistry / EventBus / ResourceRegistry
│   ├── lpr-spi/                   # ClassLoaderFactory / EventBusProvider / Metrics / DescriptorParser
│   ├── lpr-core/                  # Manager / Registry / Lifecycle / Loader / Repository
│   ├── lpr-classloader-sofaark/
│   ├── lpr-classloader-default/
│   ├── lpr-event-default/
│   ├── lpr-descriptor-snakeyaml/
│   ├── lpr-management/            # REST 管理 API（接管 KPM 的安装能力）
│   ├── lpr-testkit/
│   └── lpr-example/               # 两个依赖冲突的示例插件（PoC）
├── killbill-platform/             # 保留目录；删 osgi/osgi-api/osgi-bundles
└── killbill/                      # 保留目录；改造插件调用面
```

---

## 四、实施阶段

### ✅ Phase 0：单仓库聚合（已完成）

不改一行业务代码，只做构建层的合并。

**已执行的改动**：

1. 新建 `/pom.xml`：`packaging=pom`，**无 parent**（纯 reactor，不改变任何模块的继承关系），`<modules>` 按拓扑序列出 6 个目录
2. 5 个子仓库根 pom 补 `<relativePath>../killbill-oss-parent/pom.xml</relativePath>`，parent 版本从 4 个不同值（0.147.2 / .14 / .15 / .20）统一到 `0.147.21-SNAPSHOT`
3. `killbill-oss-parent/pom.xml` 的 4 个版本属性改成本地 SNAPSHOT 值，并**上提定义 `<killbill.version>`**（上游从未定义它）：

   | 属性 | 改前 | 改后 |
   |---|---|---|
   | `killbill-api.version` | 0.55.1 | 0.55.2-SNAPSHOT |
   | `killbill-commons.version` | 0.27.2 | 0.27.3-SNAPSHOT |
   | `killbill-platform.version` | 0.42.2 | 0.42.3-SNAPSHOT |
   | `killbill-plugin-api.version` | 0.28.1 | 0.28.2-SNAPSHOT |
   | `killbill.version` | **未定义** | 0.25.5-SNAPSHOT |

**验收结果**：

```
BUILD SUCCESS — 73 个模块，3 分 17 秒（-T 1C）
```

三项验证：
- ✅ 本地仓库未混入任何 oss-parent 原先锁定的旧版本（0.55.1 / 0.27.2 / 0.42.2 / 0.28.1 全部不存在）
- ✅ 安装的是本地 SNAPSHOT
- ✅ sortpom（绑在 verify 阶段，会自动重写 pom）保留了所有手工编辑

**待办（Phase 0 收尾）**：删除失效的多仓库联编产物——各仓库 `release.sh`、oss-parent 的 ci.yml 联编逻辑、`killbill/.circleci/config.yml`（MAVEN_OPTS 里还是 JDK 21 下已失效的 CMS GC 参数）。

---

### ✅ Phase 1：LPR 骨架（api + spi + core）（已完成）

按 V1 文档第 59 节的最小 API 集建模。核心抽象四个：`Plugin` → `PluginContext` → `{ServiceRegistry, EventBus, ResourceRegistry}`。

- **`lpr-api`**：`Plugin` / `PluginContext` / `PluginConfig` / `ServiceRegistry` / `ServiceReference` / `ServiceRegistration` / `EventBus` / `Subscription` / `ResourceRegistry` / `PluginHealth` / `PluginExecutor`
- **`lpr-spi`**：`PluginClassLoaderFactory` / `EventBusProvider` / `MetricsCollector` / `DescriptorParser`
- **`lpr-core`**：`PluginManager` / `PluginRegistry` / `PluginLifecycleManager` / `PluginLoader` / `DefaultServiceRegistry` / `PluginRepository`

状态机：`INSTALLED → RESOLVED → STARTING → ACTIVE → STOPPING → STOPPED → UNINSTALLED` + `FAILED`
用 `AtomicReference<PluginState>` + CAS 保证 START 不会并发执行两次。

> 对比现状：Kill Bill 目前只有 `RUNNING` / `STOPPED` 两态（`DefaultPluginsInfoApi.toPluginState()` 直接映射 `bundle.getState() == Bundle.ACTIVE`），所以 8 态是纯升级，不存在语义降级。

---

### ✅ Phase 2：ClassLoader PoC（已通过硬门禁）

V1 文档第 58 节明确要求按风险排序而非按模块顺序开发。

- `lpr-classloader-default`：自研 `URLClassLoader`（API parent-first + 依赖 child-first）——**已实现，作为默认后端**
- `lpr-classloader-sofaark`：委派 SOFAArk，作为"这个 port 真的可替换"的证明与备选（待实现）
- 测试插件 jar 在测试运行时用 `javax.tools.JavaCompiler` 现编现打，两个插件各自携带同名不同行为的库类

**两个必须存在的测试**（V1 第 55、56 节），两种 adapter 都要通过：

| 测试 | 判据 |
|---|---|
| **隔离性** | `pluginA.classLoader != pluginB.classLoader`，但 `PaymentPluginApi.class` 在两侧与 Core 是**同一个 Class 对象** |
| **泄漏性** | install → start → stop → drop reference → GC → `WeakReference<ClassLoader>.get() == null` |

parent-first 白名单从 `OSGIConfig` 的两坨 export 清单萃取：`org.killbill.*`、`javax.*`/`jakarta.*`、`org.joda.time`、`org.slf4j`、`org.apache.shiro`。约 10 行即可替代原来的 200 行。

> **有利条件**：现有插件 jar 是 **fat jar**（bnd `Embed-Dependency: *;inline=true`，依赖全部 inline），对自研 ClassLoader 隔离天然友好——不需要依赖解析器。

**这个 PoC 不通过，技术路线不成立，不要往下走。**

---

### ✅ Phase 3：Service Registry + Lifecycle + Resource Registry + EventBus（已完成）

- Service 生命周期严格绑定 Plugin 状态：STARTING 时注册，STOPPING 时**先切新流量 → 再注销服务 → 再关资源 → 最后关 ClassLoader**（V1 第 38 节的顺序要求，顺序错了会出现 "ClassLoader already closed"）
- `ResourceRegistry.closeAll()` 托管 subscription / executor / connection —— **这是 ClassLoader 能被 GC 的前提**
- 原子服务切换 `replace(key, old, new)`，为热更新铺路
- 搬运 `ContextClassLoaderHelper`（TCCL 切换 + `kb_plugin_latency` / `kb_plugin_errors` 埋点），metrics 走 `MetricsCollector` SPI

---

### ✅ Phase 4：插件仓库、描述符与 PluginManager（已完成；REST 管理 API 随 Phase 6 接入 jaxrs）

**新布局**：

```
plugins/<plugin-id>/
├── ACTIVE                 # metadata.json，取代 SET_DEFAULT symlink
├── 1.2.0/
│   ├── plugin.jar
│   ├── plugin.yaml        # id/name/version/entrypoint/runtime/requires/provides/config
│   └── lib/               # 可选（现有插件是 fat jar）
└── 1.3.0/
```

- `plugin.yaml` **显式声明 entrypoint 类**，取代 bnd 扫描继承关系的隐式做法
- 版本用 SemVer 比较 —— **修正现有缺陷**：`DefaultPluginConfig.compareTo` 用 `String.compareTo` 比版本，导致 `"10.0" < "9.0"`
- `lpr-management` 提供 REST：`GET /plugins`、`POST /plugins/install`、`{id}/start|stop|restart|update|rollback`、`DELETE /plugins/{id}`
- 从 KPM 搬运可复用逻辑：Maven 坐标解析、下载、sha1 校验（`CoordinateBasedPluginDownloader` / `DefaultNexusMetadataFiles` / `Sha1Checker`），其余 3756 行删除
- 保留 `disabled.txt` / `restart.txt` 的热重启语义（改为描述符驱动）

---

### ✅ Phase 5：接入 Kill Bill 核心（已完成）

1. `lpr-api` 定义 `PluginServiceRegistry<T>` / `PluginServiceDescriptor`（对应原两个接口，裁掉 `addRegistrationListener` 和 `OSGISingleServiceRegistration` —— 两者在 main 源码里都是 0 调用）
2. 6 个业务模块（catalog / currency / entitlement / invoice / payment / usage）：**改 pom 一行 + import 一行，业务逻辑零改动**
3. 8 个 registry 实现类 + 8 个 Guice Provider + 6 个 Module binding 同步改名
4. `KillbillPlatformModule.configureOSGI()` → `configurePluginRuntime()`，`install(new DefaultOSGIModule(...))` → `install(new PluginRuntimeModule(...))`
5. `DefaultOSGIService` → `PluginRuntimeService`，挂在同样的 `INIT_PLUGIN` / `START_PLUGIN` / `STOP_PLUGIN` 三个 LifecycleLevel 上

   > ⚠️ **必须保留的反直觉语义**：`START_PLUGIN` 属于 STARTUP_**PRE** 序列，插件在所有 Core service 的 `START_SERVICE` 之前就启动完了。所以插件不能在启动时立刻调 Core API 做业务，要等 `.../lifecycle/STARTED` 事件。破坏这个语义会导致插件在 Core 未就绪时崩溃。

6. 32 个测试文件同步改造（重灾区：`beatrix/TestWithFakeKPMPlugin` 12 处、`BeatrixIntegrationModule` 的 `OptionalBinder` 4 处）

---

### Phase 6：平台侧实现替换（真正的工作量）

| # | 位置 | 要做什么 |
|---|---|---|
| 1 | `KillbillActivator`（11 个 `@Inject` registry + `serviceChanged`） | OSGi `ServiceEvent → registerService` 桥 → LPR 注册回调；保留 `killbill.pluginName` 校验（小写正则 + ≤40 字符） |
| 2 | `killbill/jaxrs/PluginResource.java` :93/:96/:209/:264 | `@Named("osgi") HttpServlet` → LPR HTTP 路由；`killbill.osgi.servletContext/servletConfig` 两个 request attribute 约定重命名。`OSGIServlet` + `DefaultServletRouter` 是纯 servlet API，可搬运复用 |
| 3 | `PluginsInfoApi` / `PluginInfo` / `PluginState` + `DefaultPluginsInfoApi` | 状态映射从 2 态扩到 LPR 8 态；影响 `PluginInfoResource` / `NodesInfoResource` / `DefaultKillbillNodesService` |
| 4 | `killbill-api` 的 `PluginConfigServiceApi.getPluginJavaConfig(long bundleId)` | `bundleId` 是 OSGi 概念，改成按 pluginId 索引；删 `getPluginRubyConfig` |
| 5 | `killbill/util/nodes/DefaultNodeInfo.java` | 解掉对 `DefaultPluginsInfoApi.DefaultPluginInfo` 实现类的依赖，随后删 `util/pom.xml:210` 整条依赖 |
| 6 | `ROTenantContext`（`KillbillApiAopModule.java:126`） | 迁到新 api 包 |
| 7 | Notification 事件链 | `NotificationPluginApi` 当前**不走 registry**（全局唯一架构不一致点），借这次统一；`java.util.Observable` 的反射 hack 换成 LPR EventBus |
| 8 | `OSGIListener`（START/STOP/RESTART_PLUGIN 节点命令） | 接到 LPR `PluginLifecycleManager` |

**建议顺序**：4 → 5 → 6（先解耦）→ 1（注册桥）→ 3（PluginsInfo）→ 2（HTTP）→ 7（事件）→ 8（节点命令）

---

### Phase 7：删除 OSGi + 重写插件

- 删 `killbill-platform/osgi`、`osgi-api`、`osgi-bundles`（含 9 个 `packaging=bundle` 模块、KPM、libs/killbill、slf4j-osgi）
- 删 oss-parent 的 felix / org.osgi 依赖管理、`maven-bundle-plugin`、`osgi.*` 属性（150-223 行）
- 顺带清理死代码：`PuginConfServiceApi`（空类，类名拼错）、`PluginLanguage.RUBY` 及全部 JRuby 残迹、`OSGIKillbillLogService`、`http/StaticServlet`、`killbill/api/.../LiveTrackerException`（全仓 0 引用）
- **需按 LPR 插件形态重写**（或降级为 Core 内置能力）：`bundles/{metrics,prometheus,graphite,influxdb,eureka,logger}` 6 个功能 bundle

---

## 五、验证方式

| 阶段 | 命令 / 判据 |
|---|---|
| ✅ Phase 0 | `mvn -T 1C clean install -DskipTests` 全绿 —— **已通过** |
| Phase 2 | `mvn test -pl lpr/lpr-testkit` —— 隔离性 + 泄漏性两个测试通过，**且两种 ClassLoader adapter 都通过** |
| Phase 2 附加 | `mvn dependency:tree -pl lpr/lpr-core` 确认无 sofa / guava / micrometer / snakeyaml（Port & Adapter 门禁） |
| Phase 4 | REST 装一个 `lpr-example` 插件 → start → `GET /plugins` 状态为 ACTIVE → update 到新版本 → rollback |
| Phase 5 | `mvn test -Ptravis`（TestNG groups `fast,slow`），各业务模块单测全绿 |
| Phase 6 | `mvn test -Pintegration-mysql`（beatrix 集成测试，含 `TestWithXxxPlugin` 系列 7 个文件） |
| 全流程 e2e | `./bin/db-helper -a create` → `./bin/start-server -s` → 轮询 `/1.0/healthcheck` → 建 tenant → 装一个 LPR payment 插件 → 打一笔 payment → 验证 `/plugins/<name>/...` HTTP 端点可达 |
| 回归判据 | `grep -r "org\.osgi" --include="*.java" .` 返回 0；`grep -r "OSGI" killbill/*/src/main` 返回 0 |

---

## 六、规模评估

| 范围 | 量级 |
|---|---|
| 要删除的代码 | `osgi` + `osgi-api` + `osgi-bundles` ≈ **14,283 行 main / 117 文件** |
| 要新写的代码 | LPR 全栈，粗估 **6,000–9,000 行**（不含插件重写） |
| 主工程机械替换 | ~100 处 / 35 文件（低风险） |
| 主工程真改造 | **8 个关键点**（高风险，6/8 在 platform 侧） |
| 测试改造 | ~90 处 / 32 文件 |
| 插件重写 | 6 个平台功能 bundle + 全部业务插件（独立项目） |

Phase 0 和 Phase 2 是两个硬门禁：**Phase 0 不通过就没有工作基线，Phase 2 不通过就说明技术路线要换**。建议逐 Phase 验收。

---

## 附录：对 v2 的一处修正（ClassLoader 默认后端）

设计文档 v2 建议"ClassLoader 隔离复用 SOFAArk，第一版就用它"。实施 Phase 2 前对 SOFAArk 3.2.1 做了实证核查，结论是**保留 SPI 边界，但默认后端改为自研**。

### 证据（来自字节码，非推测）

1. `AbstractClasspathClassLoader` 与 `PluginClassLoader` 的构造器都硬调
   `ArkServiceContainerHolder.getContainer().getService(...)`，无法脱离运行中的 Ark 容器实例化。
   （这一条本身可接受：`new ArkServiceContainer(args).start()` 可以进程内启动，不必接管 JVM main。）

2. 决定性的一条 —— `ClassLoaderService` 的整个 API 围绕**插件声明式导出/导入包**构建：

   ```
   isClassInImport(pluginName, className)
   getExportMode(String)  /  findExportPlugin(String)
   isDeniedImportClass(pluginName, className)
   ```

   即 SOFAArk 用 ark-plugin MANIFEST 的 `export-packages` / `import-packages`
   替换了 OSGi 的 `Import-Package` / `Export-Package`——**结构上是同一种契约**。

### 为什么这与目标冲突

本次改造的核心动机之一，就是干掉"包白名单成为插件 ABI 契约"这件事
（`killbill-oss-parent` 里硬编码 40+ 包名，`OSGIConfig` 里约 200 行导出清单）。
采用 SOFAArk 等于把这个负担换个名字重新引入，插件作者仍需维护包声明。

对照 Kill Bill 的真实隔离需求：

| 需求 | 现状 |
|---|---|
| 插件依赖形态 | **fat jar**（bnd `Embed-Dependency: *;inline=true`，依赖全部 inline） |
| 插件间依赖 | **不支持**（v1 文档 §26 明确排除） |
| 需要的委派策略 | 一份固定的 parent-first 白名单 |

即：没有依赖图要解析，没有跨插件导出表要维护——而这两样正是模块系统体积的来源。
剩下的只有委派顺序，就是 `ClassLoaderPolicy` 那几十行。

### 实际结果

`lpr-classloader-default` 用约 200 行 `URLClassLoader` 实现，通过全部 8 项测试：

- **隔离性**：两个插件各自持有 Guava-风格冲突库的独立副本，同时与 Core 共享同一个 API `Class` 对象
- **可卸载性**：install → start → stop → 丢引用 → GC → `WeakReference.get() == null`
- **防遮蔽**：插件自带的 API 类副本无法覆盖运行时的
- **并行加载**：同一 artifact 加载两次得到独立 ClassLoader（热更新的前提）

原先 OSGi 里那两坨约 200 行的 system-packages 导出清单，被压缩成
`ClassLoaderPolicy` 里的 13 个包前缀。

### SPI 边界仍然保留

`PluginClassLoaderFactory` 这个 port 不变，`lpr-core` 只依赖它。
SOFAArk adapter 仍会实现并跑同一套测试，作为"这个 port 是真的可替换"的证明与备选后端。
`TestArchitecturalConstraints` 会在 `lpr-core` 意外看到 SOFAArk / Guava / Micrometer / SnakeYAML 时失败。

### 一个副产物：JDK 21 的线程引用行为

Phase 2 的泄漏测试暴露了一个此前不知道的约束：

```
持有已终止的 Thread 对象时, 被回收? false
丢弃 Thread 对象后,        被回收? true
```

**JDK 21 中已终止的 `Thread` 对象仍强引用它的 `Runnable`**（旧版 JDK 的 `Thread.exit()`
会把 `target` 置空，虚拟线程重构后不再如此）。

后果：`ResourceRegistry` 必须**释放引用**，而不能只调 `shutdown()`。一个已 shutdown
但仍被字段引用的 `ExecutorService`，会通过 worker 线程的 task 继续钉住插件 ClassLoader，
使"卸载"变成空操作。已固化为 CLAUDE.md 的约束 A4，由
`TestClassLoaderUnloading.testLeakedThreadKeepsClassLoaderAliveUntilItStopsAndIsReleased` 守护。


---

## 附录二：Phase 5 的实际执行与验证

### 做了什么

新建 `killbill-platform/plugin-api`，取代 `killbill-platform-osgi-api`：

| 原 | 新 | 变化 |
|---|---|---|
| `OSGIServiceRegistration<T>` | `PluginServiceRegistry<T>` | 去掉 `addRegistrationListener`（main 源码 0 调用） |
| `OSGIServiceRegistrable<T>` | （合并进上者） | 两层继承没有价值 |
| `OSGIServiceDescriptor` | `PluginServiceDescriptor` | 砍掉 `getPluginSymbolicName()` / `getPluginName()`——**核心从未读取过它们**（内建插件的实现里 `getPluginSymbolicName()` 直接 `return null`），改为 `getPluginId()` / `getPluginVersion()` |
| 匿名 `new OSGIServiceDescriptor(){...}` | `DefaultPluginServiceDescriptor.builtIn(name)` | 21 处 3 方法匿名类塌缩成一行 |
| `OSGISingleServiceRegistration<T>` | （删除） | main 源码 0 引用 |

改动规模：**60 个文件**（35 个 main + 25 个 test），其中 21 处匿名 descriptor 被塌缩。
6 个业务模块（catalog / currency / entitlement / invoice / payment / usage）**业务逻辑零改动**，
只改了 pom 一行 + import 一行。

### 验证结果（PostgreSQL）

| 模块 | 测试数 | 结果 |
|---|---:|---|
| catalog | 117 | ✅ |
| entitlement | 228 | ✅ |
| usage | 6 | ✅ |
| invoice | 548 | ✅ |
| payment | 186 | ✅ |
| account（**未改动**，环境对照组） | 43 | ✅ |
| **合计** | **1128** | **全绿** |

---

## 附录三：Phase 6 / 7 的当前进度

### Phase 6：已完成的部分

新建 `killbill-platform/plugin-runtime`，取代 `killbill-platform-osgi`：

- **`KillbillPluginServiceBridge`** — LPR 的单一注册表 → Kill Bill 的类型化注册表。
  取代 `KillbillActivator.serviceChanged`。**关键改进**：原来平台必须在 11 个 `@Inject` 字段里
  逐一写死每种插件服务类型，新增一种就要改平台；现在业务模块通过 Guice `Multibinder` 自己贡献，
  平台不需要知道 payment / invoice 这些类型的存在（约束 A6）。
- **`PluginServiceContextClassLoaderProxy`** — TCCL 切换，`ContextClassLoaderHelper` 的等价物。
  这个能力与 OSGi 无关，来自 Java 反射式查找类的方式（`ServiceLoader` / JDBC / JAXB），必须保留。
- **`PluginRuntimeService`** — 挂在原有的 `INIT_PLUGIN` / `START_PLUGIN` / `STOP_PLUGIN` 三个
  LifecycleLevel 上，保留"插件早于 Core service 启动"的既有语义。
- **`PluginRuntimeConfig`** — 4 个配置项，原 `OSGIConfig` 有十几个（其中约 200 行是 OSGi
  system-packages 导出清单，现由 `ClassLoaderPolicy` 的 13 个前缀取代）。
- **`PluginRuntimeModule`** — 取代 `DefaultOSGIModule`。

### Phase 6：全部完成

上一版这里列了六项未完成，现在都已落地：

| 项 | 实现 | 验证 |
|---|---|---|
| 装配接入 | `KillbillPlatformModule.configurePluginRuntime()` | `TestPluginRuntimeModule` |
| HTTP 路由 | `PluginServlet` + `PluginServletRouter` | `TestPluginLifecycleAndHttpEndToEnd` 一路打到响应体 |
| `PluginsInfoApi` | `plugin-runtime/DefaultPluginsInfoApi`，八态收敛成两态 | 同上 |
| `NotificationPluginApi` 事件分发 | `PluginEventDispatcher`，**已统一到 registry** | `TestPluginRuntimeModule` |
| 节点命令 | `PluginNodeCommandListener`，含 KPM 遗留的 INSTALL/UNINSTALL | — |
| `util` 解耦 | `DefaultNodeInfo` 不再引用 osgi 实现类 | 全量构建 |

**平台服务发布**是清点时才发现的第七项缺口：`platformServices()` 一直返回空 Map，
插件拿不到任何平台能力。现由 `PlatformServices` 按一份声明式类型清单
（20 个业务 API + `Clock` / `DataSource` / `KillbillConfigSource` / `MetricRegistry`）解析发布。

它用 `Injector.getExistingBinding` 而非构造器注入，**刻意不复制 OSGi 的隐性耦合**：
`DefaultOSGIModule` 用 `@Inject` setter 注入全部 21 个 API，等于"装了插件运行时就必须绑齐它们"，
纯支付 profile 或平台级测试都做不到。现在缺哪个就不发布哪个。

### Phase 7：已完成的部分

**hello-world 插件已迁移并端到端跑通**（`plugins/hello-world-plugin`）。

验证链路（`TestHelloWorldPluginMigration`，从**真实 jar** 加载而非测试 classpath）：

1. 插件被发现、启动，状态 `ACTIVE`
2. 出现在 Kill Bill **真实的** `DefaultPaymentProviderPluginRegistry` 里
3. 一笔 `purchasePayment` 打到插件，返回真实的 `PaymentTransactionInfoPlugin`
4. `plugin.yaml` 的配置确实到达插件
5. 插件类由插件 ClassLoader 加载，但 `PaymentPluginApi` 与 Core 是同一个 `Class` 对象
6. 停止后从注册表消失

迁移方法沉淀为 [`plugin-migration-guide.md`](plugin-migration-guide.md)。

**这次迁移抓出一个真实设计缺陷**：`ClassLoaderPolicy` 原本把整个 `org.killbill.` 判为 parent-first，
导致插件自己的类（`org.killbill.billing.plugin.helloworld.*`）被交给平台加载。生产环境靠"平台找不到
就回落"歪打正着，但只要出现同名类就会静默拿到平台副本。已修正为把 `org.killbill.billing.plugin.`
挖回 child-first。

### Phase 7：全部完成

- OSGi 三个模块树已删除；`import org.osgi` 全仓 0 处，pom 里 felix / `maven-bundle-plugin` 0 处，
  `packaging=bundle` 0 个
- **6 个平台功能 bundle 的处置**：
  - `metrics` / `prometheus` / `graphite` / `influxdb` / `eureka` → 已重写为 LPR 插件（`plugins/`）
  - `logger` → **不迁移**。它的主体是 OSGi `LogService` → slf4j 的桥接，
    随 OSGi 一起作废是正确结果，而不是遗漏
- **KPM 的替代**：`lpr-management` 提供取件、校验、按仓库布局落盘、卸载；
  接在既有的 `INSTALL_PLUGIN` / `UNINSTALL_PLUGIN` 节点命令上。
  KPM 另外 3,500 行是 Kill Bill Cloud 的插件目录查询（Nexus 元数据、token 鉴权），
  属于"目录服务"而非"安装器"，不在替代范围

**管理面为什么不是一套新 REST**：Kill Bill 已有 `/1.0/kb/nodesInfo` 这条命令通道，
它自带鉴权、审计和集群广播。再造一套并行 REST 意味着这三样都要重做一遍。

---

## 附录四：OSGi 删除的实际范围（Phase 6/7）

### 删掉了什么

| 模块 | 文件 | 行数 |
|---|---:|---:|
| `killbill-platform/osgi` | 31 | 4,402 |
| `killbill-platform/osgi-api` | 8 | 401 |
| `killbill-platform/osgi-bundles`（含 KPM、插件 SDK、6 个功能 bundle、2 个测试 bundle） | 102 | 11,605 |
| **合计** | **141** | **16,408** |

外加：
- `killbill-oss-parent` 里 Felix / `org.osgi` 的全部依赖管理、`maven-bundle-plugin`、
  以及那套 `osgi.*` bnd 属性（含约 50 行的 `Import-Package` 白名单）
- `killbill-api` 的 `org.killbill.billing.osgi.api` 包
- OSGi 专属的 DataSource / EmbeddedDB 配置（`org.killbill.billing.osgi.dao.*`）与相应的
  Guice 绑定、生命周期关闭逻辑
- 全部 `packaging=bundle`（现在 0 个模块用 bnd 打包）

reactor 从 85 个模块降到 67 个。

### 替代关系

| 原 | 新 | 归属模块 |
|---|---|---|
| `OSGIServiceRegistration<T>` / `OSGIServiceRegistrable<T>` | `PluginServiceRegistry<T>`（合并为一层） | `killbill-platform-plugin-api` |
| `OSGISingleServiceRegistration<T>` | `SinglePluginServiceRegistry<T>` | 同上 |
| `OSGIServiceDescriptor`（5 个 getter） | `PluginServiceDescriptor`（3 个） | 同上 |
| `OSGIConfigProperties` | `KillbillConfigProperties` | `killbill-platform-api` |
| `ROTenantContext` | `ReadOnlyTenantContext` | `killbill-platform-plugin-api` |
| `org.killbill.billing.osgi.api.*`（PluginInfo 等 7 个） | `org.killbill.billing.runtime.api.*` | `killbill-api` |
| `OSGIKillbill`（20 方法门面） | 删除，改用 `PluginContext.getPlatformService(Type.class)` | — |
| `OSGIPluginProperties.PLUGIN_NAME_PROP` | `PluginServiceProperties.REGISTRATION_NAME`（可选，默认取 pluginId） | `killbill-platform-plugin-api` |
| `KillbillActivator.serviceChanged`（11 个写死的 `@Inject`） | `KillbillPluginServiceBridge` + Guice `Multibinder` | `killbill-platform-plugin-runtime` |
| `ContextClassLoaderHelper` | `PluginServiceContextClassLoaderProxy` | 同上 |
| `DefaultOSGIModule` | `PluginRuntimeModule` | 同上 |
| `DefaultOSGIService` | `PluginRuntimeService`（同样的三个 LifecycleLevel） | 同上 |
| `OSGIServlet` / `DefaultServletRouter` | `PluginServlet` / `PluginServletRouter` | 同上 |
| `OSGIConfig`（十几项，含 200 行包导出清单） | `PluginRuntimeConfig`（4 项） | 同上 |
| `MetricRegistryServiceRegistration` 等每类型一个的注册表 | `MapBackedPluginServiceRegistry<T>` / `SingleValuePluginServiceRegistry<T>` | 同上 |
| `PluginFinder` / `FileInstall` / `BundleRegistry` / `SET_DEFAULT` | `PluginRepository` + `ACTIVE` 文件 | `lpr-core` |
| `PluginConfig` / `PluginJavaConfig` / `PluginConfigServiceApi`（按 `bundleId` 索引） | `PluginDescriptor` + `PluginArtifact` + `PluginContext.config()` | `lpr-spi` / `lpr-api` |
| KPM（3756 行，插件形态的插件管理器） | `DefaultPluginManager`（install / start / stop / activateVersion / rollback） | `lpr-core` |
| `PluginLanguage` / `PluginRubyConfig` / JRuby 全部残迹 | 删除 | — |

### 顺带修正的既有缺陷

- **版本比较**：原来用 `String.compareTo`，`10.0` 排在 `9.0` 之前 → 改为 SemVer 数值比较
- **jar 选取**：原来取目录里第一个 `.jar`（依赖遍历顺序）→ 改为 `plugin.jar` 优先，歧义则报错
- **`SET_DEFAULT` 符号链接** → `ACTIVE` 文件（符号链接在打包/复制/镜像层间行为不一致）
- **`getBundleSymbolicName()`**：一路渗透到持久化 JSON 与 REST 响应，但核心从未读取过 → 移除
- **`addRegistrationListener`**：补上"添加时若已有实现则立即回放"，否则后加的监听器会永远等待
