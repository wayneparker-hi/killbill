# 问题账本

实施过程中真实遇到的问题、根因、解决方式，以及**沉淀到了哪里**。

写在这里的目的不是记流水账，而是让"这个坑已经踩过"变成可复用的东西。所以每条都标注了它最终变成了什么——一条约束、一个守卫测试、一段文档，还是仅仅是一次性的修复。

按"值不值得记住"排序，不按时间。

---

## 一、改变了架构决策的问题

### 1. SOFAArk 不适合做默认 ClassLoader 后端

**背景**：设计文档 V2 明确建议"ClassLoader 隔离复用 SOFAArk，第一版就用它"，用户也据此拍板。

**怎么发现的**：在写任何代码之前，先把 `sofa-ark-container:3.2.1` 拉下来用 `javap` 看真实形状。

**发现**：
1. `AbstractClasspathClassLoader` 和 `PluginClassLoader` 的构造器都硬调
   `ArkServiceContainerHolder.getContainer().getService(...)` —— 无法脱离运行中的 Ark 容器实例化。
   （这条本身可接受：容器能进程内启动。）
2. **决定性的**：`ClassLoaderService` 的整个 API 围绕插件**声明式导出/导入包**构建
   （`isClassInImport` / `getExportMode` / `findExportPlugin` / `isDeniedImportClass`）。

**为什么这与目标冲突**：SOFAArk 用 ark-plugin MANIFEST 的 `export-packages`/`import-packages` 替换了 OSGi 的
`Import-Package`/`Export-Package`——**结构上是同一种契约**。而干掉"包白名单成为插件 ABI"正是本次改造的动机之一。

对照真实需求：Kill Bill 插件是 fat jar、不支持插件间依赖、只需要一份固定的 parent-first 白名单。
没有依赖图要解析，没有跨插件导出表要维护——而这两样正是模块系统体积的来源。

**解决**：向用户说明证据并请其重新裁决 → 改为自研为默认，SOFAArk adapter 保留为可替换性证明。
自研实现约 200 行，原 `OSGIConfig` 里约 200 行的 system-packages 清单压缩成 13 个包前缀。

**沉淀**：[`../migration/osgi-to-lpr-plan.md`](../migration/osgi-to-lpr-plan.md) 附录「对 v2 的一处修正」

**可复用的教训**：**在写代码前先验证第三方库的真实形状，而不是照着文档/记忆假设它能怎么用。** 这次省下的是把
SOFAArk 深度集成之后才发现模型不匹配的返工。

---

### 2. JDK 21 中已终止的 Thread 仍强引用它的 Runnable

**现象**：泄漏测试的后半段失败——插件线程已经停止，ClassLoader 仍然回收不掉。

**排查**：没有猜，写了个 20 行的探针程序实测：

```
持有已终止的 Thread 对象时, 被回收? false
丢弃 Thread 对象后,        被回收? true
```

**根因**：旧版 JDK 的 `Thread.exit()` 会把 `target` 置空，虚拟线程重构后不再这样做。
所以只要还有人持有那个 `Thread` 对象，lambda 捕获的一切就都活着。

**影响**：这不是测试问题，是**运行时的真约束**。一个已经 `shutdown()` 但仍被字段引用的
`ExecutorService`，会通过 worker 线程的 task 继续钉住插件 ClassLoader，使"卸载插件"变成空操作，
反复热更新就是 metaspace 泄漏。

**解决**：
- `ResourceRegistry` 的契约明确为"**释放引用**"而非"关闭"，实现里 `closeAll()` 边释放边清空自己的列表
- `PluginRuntime.unloaded()` 显式丢弃 instance / context / classLoaderHandle
- 测试改成三阶段，分别断言"线程运行中钉住"→"线程终止但仍被引用时仍钉住"→"丢弃引用后可回收"

**沉淀**：CLAUDE.md 约束 A4；守卫 `TestClassLoaderUnloading.testLeakedThreadKeepsClassLoaderAliveUntilItStopsAndIsReleased`
（其 Phase 2 同时是哨兵：哪天 JDK 行为变了它会失败，届时约束可放宽）

**可复用的教训**：**涉及 GC / ClassLoader / 线程引用的假设，写探针实测，不要凭记忆断言。**

---

## 二、被测试抓到的真 bug

### 3. 服务撤销粒度过粗，升级时会把新版本的服务一起撤掉

**现象**：端到端测试 `testUpgradingLeavesNoWindowWithoutAProvider` 和
`testRollbackReturnsToThePreviousVersion` 失败：
`Plugin versioned-payment publishes no PaymentGateway; publishers: []`——升级完成后插件什么都不提供。

**根因**：`activateVersion` 的顺序是"先启动新版本，再停掉旧版本"（这是无空窗升级的正确做法）。
但停掉旧版本时调的是 `serviceRegistry.unregisterAll(pluginId)`——而**新旧两个版本共享同一个 pluginId**，
于是刚注册好的新版本服务被一并撤销。插件状态是 ACTIVE，却什么都不提供。

**解决**：注册按 `(pluginId, version)` 作用域化，`unregisterAll(pluginId, version)`。
保留 `unregisterAll(pluginId)` 用于整体关停。

**沉淀**：CLAUDE.md 约束 A5；守卫就是抓到它的那两个端到端测试

**可复用的教训**：**端到端测试的价值在于抓跨组件的语义错配。** 这个 bug 在
`DefaultServiceRegistry` 和 `DefaultPluginManager` 各自的单元测试里都不会暴露——两边单独看都是对的。

---

### 4. `DefaultEventBus` 的 subscribe/unsubscribe 竞态

**怎么发现的**：不是测试抓到的，是写完之后自己复查发现的。原实现：

```java
// subscribe
subscriptionsByType.computeIfAbsent(eventType, t -> new CopyOnWriteArrayList<>()).add(subscription);

// unsubscribe
peers.remove(subscription);
if (peers.isEmpty()) {
    subscriptionsByType.remove(eventType, peers);   // ← 两个 bug
}
```

**两个问题**：
1. `remove(key, value)` 用 `equals` 比较，而 `peers` 就是 map 里那个对象本身，**恒为 true**——
   即使并发的 subscribe 刚往里加了元素，也会被删掉。
2. `computeIfAbsent(...).add(...)` 的 add 在原子块**外面**，会落进一个已被移除、不再可达的 list 里。

两者叠加：并发下订阅会静默丢失，之后再也收不到事件。

**解决**：subscribe 的 add 放进 `compute` 内部，unsubscribe 用 `computeIfPresent` 在同一个原子操作里
完成"移除 + 判空 + 删桶"。两者对同一 key 互斥。

**沉淀**：守卫 `TestDefaultEventBus.testConcurrentSubscribeAndUnsubscribeNeverLosesASubscription`（500 轮竞态）

**可复用的教训**：`ConcurrentHashMap.remove(key, value)` 在 value 就是 map 内对象时是个陷阱；
"取出容器再修改"和"在 compute 里修改"是两种不同的原子性。

---

## 三、环境与工具坑

### 5. Maven 实际跑在 JDK 8 上，`export JAVA_HOME` 无效

**现象**：
```
sortpom/SortMojo has been compiled by a more recent version of the Java Runtime
(class file version 55.0), this version only recognizes up to 52.0
```

**根因**：`mvn` 是 `~/.jenv/shims/mvn`，**jenv shim 会用自己的版本覆盖 export 的 `JAVA_HOME`**。
jenv 全局默认是 JDK 8。

**解决**：绕开 shim 用绝对路径 `/Users/wayne/maven/apache-maven-3.6.3/bin/mvn`（脚本场景），
或 `jenv shell 21`（交互场景）。

**沉淀**：CLAUDE.md 环境坑表；[`build-environment.md`](build-environment.md)

---

### 5b. 嵌入式 MySQL 在 Apple Silicon 上没有二进制包，所有 DB 测试跑不了

**现象**：任何带 DB 的测试在 `@BeforeSuite` 就挂，`archive not found: /mysql-Mac_OS_X-aarch64.tar.gz`，
**整个 suite 被跳过**（`Tests run: 139, Skipped: 138`）。

**先做的事：确认与自己的改动无关。** 当时刚做完 6 个业务模块的接口重命名，很容易归咎于它。
验证方式是跑一个**完全没改过**的模块（`killbill/account`，0 个文件改动）——报同样的错，
于是确定是环境问题而非回归。

**解决**：接 OrbStack dev-infra 的 PostgreSQL。`PlatformDBTestingHelper.isUsingLocalInstance()` 支持外部 DB，
但该模式下它**不建表**（`start()` 提前 return），schema 要按
`DBTestingHelper.executePostStartupScripts()` 的确切顺序预先灌好（13 个 DDL 文件，68 张表）。

**中途两个卡点**：
1. 官方 `bin/db-helper` 依赖 gnu-getopt，本机 `/usr/local/opt/gnu-getopt` 是**悬空 symlink**（实际没装）。
   没去装，直接用 Python 复刻了它的 `create_ddl_file` 逻辑——顺带能精确控制顺序。
2. **`-D` 属性传不进 surefire fork 的 JVM**：`mvn test -Dorg.killbill...postgresql=true` 完全不生效，
   日志仍是 `Using MySQL as the embedded database`。必须走 `-DsurefireArgLine=...`。
   且覆盖时要把 `javac17-release` profile 原有的四个 `--add-opens` 一起带上，否则换一批测试挂。

**验证**：未改动的 `account` 模块 43 项测试全绿，日志显示 `Using PostgreSQL local database`。

**沉淀**：[`build-environment.md`](build-environment.md) 坑 4 / 4a（含可直接复制的命令）

**可复用的教训**：**看到测试失败，先确认是不是自己改的。** 挑一个自己完全没碰过的模块跑一遍，
一分钟就能把"环境问题"和"回归"分开——比盯着 diff 猜快得多。

---

### 9. 边跑测试边重装依赖 jar，制造了一次假回归

**现象**：invoice 模块 61 个测试失败，全是 `Unable to create injector ... Failed to initialize ClassFinder`，
根因 `ClassNotFoundException: org.killbill.billing.platform.plugin.api.PluginServiceProperties`。

**根因**：**我自己造成的竞态。** invoice/payment 的测试在后台跑，我同时在给
`killbill-platform-plugin-api` 加新类并 `mvn install`，替换了测试 classpath 上的 jar。

`DefaultLifecycle` 启动时用 `ServiceFinder` 扫描所有含 "killbill" 的 jar 找 `KillbillService` 实现：
它先枚举 jar 里的类名，再逐个 `Class.forName`。枚举时看到了新写入的 `PluginServiceProperties`，
加载时 jar 已经被下一次 install 换掉——于是 `ClassNotFoundException`。

**解决**：等 jar 稳定后重跑。代码本身没问题。

**教训**：**不要在依赖某个 artifact 的测试运行期间重装那个 artifact。**
并行推进（一边跑测试一边写下个阶段的代码）本身没错，但要避开同一条依赖链：
改 `lpr-*` / `killbill-platform-*` 时，正在跑的 `killbill/*` 测试就依赖它们。

这类失败尤其有迷惑性——它看起来完全像一次真回归（同一批模块、刚改完代码、错误指向新加的类）。
分辨方法还是[问题 5b](#5b-嵌入式-mysql-在-apple-silicon-上没有二进制包所有-db-测试跑不了) 那条：
**先确认是不是自己改的**，而这次答案是"是，但不是改错了，是改的时机不对"。

---

### 6. `mvn ... | tail` 吞掉退出码

**现象**：后台任务报告 `exit code 0`，但日志里其实是 `BUILD FAILURE`。

**根因**：管道的退出码是最后一个命令（`tail`）的。

**解决**：重定向到文件再 grep，或不接管道。

**沉淀**：CLAUDE.md 环境坑表

**可复用的教训**：任何"命令成功了吗"的判断，都要确认读的是那个命令自己的退出码。

---

### 7. XML 注释里不能出现 `--`

**现象**：`Non-parseable POM: in comment after two dashes (--) next character must be > not \n`

**根因**：我在 pom 注释里写了破折号 `-- the highest-risk item goes first`。XML 规范禁止注释内出现 `--`。

**解决**：改写措辞。注意 `<description>` 等**元素内容**里 `--` 是合法的，只有注释里不行。

**⚠️ 这条复发过一次。** 第一次踩在 `lpr/pom.xml`，记进账本后，几十次工具调用之后又踩在 `lpr-spi/pom.xml`——
同样是想用破折号做插入语。说明"记下来"本身不解决问题：这类错误发生在书写的瞬间，不在回顾的时候。

真正管用的是**改写习惯而不是增加记忆负担**：在 XML 注释里不要用 `--` 作插入语，改用句号断句。
本仓库的 pom 注释一律遵循这一点。

---

### 8. TestNG 的 `assertThrows` 返回 void

**现象**：`不兼容的类型: void无法转换为IllegalArgumentException`

**根因**：习惯了 JUnit 5 的 `assertThrows` 返回异常对象。TestNG 里返回异常的是 **`expectThrows`**，
`assertThrows` 返回 void。

**解决**：需要断言异常信息时用 `expectThrows`。

**可复用的教训**：这个仓库用 TestNG（7.7.0）+ group 划分（`fast` / `slow` / `mysql` / `integration`），
不是 JUnit。写测试前先看邻近代码的写法。

---

### 10. 单仓库合并让 `maven-jar-plugin` 的 exclude 悄悄失效

**现象**：删完 OSGi 后，`duplicate-finder` 报
`Found duplicate and different resources: org/killbill/billing/beatrix/ddl.sql`。

**先排除的误判**：第一反应是"删 OSGi 删坏了"。但两份 `ddl.sql` 内容一直就不同
（beatrix 40 行，platform-test 139 行），而且**两份都是必需的**——`DBTestingHelper` 用
`ClassLoader.getResources()` 枚举 classpath 上所有同名资源并逐个执行。

**真正的根因**：`killbill-platform-test` 用 `maven-jar-plugin` 的 `<excludes>` 把这个文件排除出自己的
jar，这在多仓库时代是够的。但**在同一个 reactor 里，duplicate-finder 解析兄弟模块时拿到的是
`target/classes` 而不是 jar**，排除项完全不生效。

验证：`unzip -l platform-test.jar | grep beatrix/ddl` → 空（排除生效）；
`ls platform-test/target/classes/org/killbill/billing/beatrix/` → 有。

**所以这是单仓库合并的副作用，不是删 OSGi 造成的**，只是被这次改动的构建顺序变化暴露出来。

**解决**：在 `killbill/pom.xml` 加 `ignoredResourcePattern`，并把上面这段原因写进注释——
否则下一个人只会看到"忽略一个重复文件"，不知道为什么不能靠 jar 的 exclude 解决。

**可复用的教训**：**把模块搬进同一个 reactor，会改变依赖解析的形态。**
凡是作用在"打包产物"上的配置（jar exclude、shade relocation、assembly 过滤），
在 reactor 内部可能根本不参与——因为下游拿到的是 `target/classes`。

---

### 11. "0 处调用"的结论要看清统计范围

**现象**：删掉 `addRegistrationListener` 后，`killbill-platform/server` 编译失败——
`KillbillPluginsMetricRegistry.gauge()` 在用它。

**根因**：分析报告的原话是"main 源码 0 调用"，但那份统计的范围是 **killbill 业务核心**，
不含 `killbill-platform`。我把它当成了"全仓 0 调用"。

而且这个方法有正当理由存在：gauge 只注册一次且调用方不保留引用，如果提供 MetricRegistry 的插件
还没启动，gauge 就永远不会出现——需要一个回调在插件到达时补登记。

**解决**：在 `SinglePluginServiceRegistry` 上补回该方法（只有单值注册表需要），
实现里额外做一件原版没做的事：**添加监听器时若已有实现，立即回放一次**，
否则后加的监听器会永远等一个可能不再发生的变更。

**可复用的教训**：**引用统计的边界要和结论的边界一致。**
"0 处调用"必须说清是哪个范围内的 0 处；跨模块删 API 前，按全仓再查一次。

---

### 12. 一个 MySQL-only 的测试，让 15 个下游模块全部跳过

**现象**：`killbill` 子树跑测试，`killbill-util` 挂在一个测试上，
`profiles` 等 15 个下游模块状态全是 SKIPPED——包括最想验证的全服务器启动测试。

```
PSQLException: null value in column "grp_id" of relation "invoices" violates not-null constraint
```

**根因**：`TestInternalCallContextFactory` 用裸 SQL 插 `invoices`，没有给 `grp_id` 赋值：

```java
handle.execute("insert into invoices (id, account_id, invoice_date, target_date, currency,
                status, migrated, created_by, created_date, account_record_id, tenant_record_id)
                values (?, ?, ?, ?, 'USD', 'COMMITTED', FALSE, 'test', ?, ?, ?)", ...);
```

而 `invoice/ddl.sql:134` 声明的是 `grp_id varchar(36) NOT NULL`，**没有默认值**。
MySQL 非严格模式会悄悄填一个空串，PostgreSQL 直接拒绝。

**判定为既有缺陷而非回归的依据**：这段 SQL 和 DDL 都在本次改造的改动面之外，
且失败原因与插件运行时无关。同样的判别方法在 issue #9 用过——
**先确认未改动的代码能否复现，再决定它是不是回归。**

**为了排除它，连踩两个 surefire 的坑**：

坑一，`-Dtest='!TestInternalCallContextFactory#testXxx'` 方法级排除**不生效**。
Kill Bill 的测试基类 `GuicyKillbillTestSuite` 实现了 TestNG 的 `IHookable`，
真正的执行栈是 `TestInternalCallContextFactory > GuicyKillbillTestSuite.run:213 -> testXxx`，
surefire 的 `#method` 过滤匹配不上这层包装。

坑二（更隐蔽），改成类级 `-Dtest='!TestInternalCallContextFactory'` 后，
`killbill-usage` 冒出一个全新错误：

```
An error occurred while instantiating class org.killbill.billing.usage.UsageTestSuiteNoDB
```

差点被当成回归。真相是：**`-Dtest` 会整体替换 surefire 的 include 模式**
（默认是 `Test*` / `*Test` / `*Tests` / `*TestCase`）。
一个纯否定的过滤器 `!X` 语义上等于"除 X 外全都要"，于是
`UsageTestSuiteNoDB` 这种**不匹配默认命名、本来就不该被当测试类**的基类
也被交给了 TestNG，脱离 Guice 环境实例化必然失败。

正确写法是保留显式 include：`-Dtest='Test*,!TestInternalCallContextFactory'`。
本例更省事的做法是**根本不用过滤**——那个测试只在 `util` 里，
util 已经跑完，用 `-rf :killbill-usage` 从下游续跑即可。

**排查时自己又制造了一次假象**：为验证"是不是过滤器造成的"，我单跑 `-pl usage` 做对照，
结果它以另一个原因失败（`archive not found: /mysql-Mac_OS_X-aarch64.tar.gz`）——
因为对照组命令里漏了 PG 那几个参数，退回了嵌入式 MySQL。
补上参数后 `usage` 干净通过 6 项测试，假设才真正被证实。

**可复用的教训**：
1. **一个失败测试的代价不是一个测试**——`-fae` 只让同级模块继续，下游依赖模块照样 SKIPPED。
   验证被阻塞时，优先考虑 `-rf` 从下游续跑，而不是给全量跑加过滤器。
2. **`-Dtest` 是替换而非叠加**。纯否定过滤会连测试基类一起扫进来，
   制造出看起来像回归、实际是工具行为的失败。
3. **对照组必须只差一个变量**。我的对照组同时少了 PG 参数，
   于是得到一个和原问题无关的失败，一度证伪了正确的假设。
4. **跨数据库跑既有测试套件，第一类会炸的就是裸 SQL 的 INSERT**：
   MySQL 非严格模式对 `NOT NULL` 无默认值的列自动填零值，PostgreSQL 不会。
5. **测试基类用了 `IHookable`/`IInvokedMethodListener` 时，surefire 的方法级过滤不可靠**。

---

### 13. 删掉一个模块，等于删掉它提供的所有 Guice 绑定

**现象**：beatrix 全部集成测试在 `TestIntegrationBase.beforeClass` 挂掉，9 秒内全灭：

```
[Guice/MissingImplementation]: No implementation for PluginsInfoApi was bound.
Requested by: DefaultKillbillNodesService.<init> for 2nd parameter pluginInfoApi
  at NodesModule.installUserApi -> installed by: BeatrixIntegrationModule -> NodesModule
```

**根因**：`PluginsInfoApi` 的唯一生产实现 `DefaultPluginsInfoApi` 住在 `killbill-platform/osgi`，
由 `DefaultOSGIModule` 绑定。我删掉了这个模块，`PluginRuntimeModule` 却没接上这个绑定。

**为什么排查绕了一大圈**：Guice 的报错指向 `NodesModule`（请求方），
而缺失的绑定来自一个**完全不同的模块**。顺着报错找 `NodesModule` 和 `BeatrixIntegrationModule`
都没有问题——我甚至逐行 diff 了上游版本，确认自己的改动全是机械重命名。
真正的线索是反过来问："上游 beatrix 是从哪拿到这个绑定的？"
全仓 grep `bind(PluginsInfoApi.class)` 只有两处，一处是测试 mock，
另一处正是 `DefaultOSGIModule` —— 而 beatrix 通过
`GuicyKillbillTestWithEmbeddedDBModule` → `TestPlatformModule` → `DefaultOSGIModule` 间接拿到。

**解决**：
1. 在 `plugin-runtime` 写 `DefaultPluginsInfoApi`，把 LPR 的八态生命周期收敛成
   Kill Bill 对外的 `RUNNING`/`STOPPED` 两态（收敛点集中在一处，理由写在注释里）。
2. `KillbillNodesApi` 与 `PluginsInfoApi` 之间是**真循环**。
   旧代码用一个可变的 `KillbillNodesApiHolder` + setter 注入来破环；
   改用 `Provider<KillbillNodesApi>` 注入——同样能破环，但没有可变字段，
   也没有"holder 已构造但还是空"的时间窗。
3. `DefaultServiceRegistry` 补 `publishedServices(pluginId)`，
   让信息 API 能同时拿到服务类型和注册属性；
   注册名的解析规则提为 `KillbillPluginServiceBridge.registrationNameOf` 的单一真源，
   保证"报告出来的名字"和"能查到的名字"永远一致。

**补的守卫**：`TestPluginRuntimeModule` 按平台的方式创建注入器，断言
`PluginsInfoApi` 能取到。**已验证它真的会失败**：临时摘掉那行绑定后，
它以和 beatrix 一模一样的 Guice 报错在 **0.6 秒**内挂掉，而不是要跑完整个集成测试套件。

这个守卫的断言刻意针对**模块的对外契约**，而不是本包里的任何一个类——
因为 `plugin-runtime` 自己根本不用 `PluginsInfoApi`，消费者在三个模块之外。

**可复用的教训**：
1. **删模块前，先列出它 `bind()` 了什么，而不是它 `class` 了什么。**
   删掉实现类会编译报错，删掉绑定只会在运行时报错，而且报错位置离原因很远。
2. **Guice 的 "Requested by" 指的是请求方，不是缺失方。**
   顺着它找只会看到无辜的模块；应该反过来问"这个绑定原本由谁提供"。
3. **给装配层写守卫，断言的对象是"外部要什么"，不是"我有什么"。**
   一个模块最容易漏的正是自己不用、只供别人用的绑定。

---

### 14. 两个伪装成回归的环境失败（locale 与自增序列）

改造收尾时，`beatrix`（492 项）和 `profiles/killbill`（309 项）各挂 1 项。
两个都不是回归，但都需要**先证实再下结论**，不能靠"看起来像"。

**其一，语言环境**：

```
expected [Invalid content was found starting with element 'duration'...]
but found [发现了以元素 'duration' 开头的无效内容。...]
```

测试断言 XSD 校验器的英文原文，JDK 按 `user.language` 返回了中文。
加 `-Duser.language=en -Duser.country=US` 后通过。

**其二，自增序列**：

```
expected [trebuchet-usage-in-arrear-1] but found [trebuchet-usage-in-arrear-37]
```

第一反应是测试隔离问题（前面的测试用掉了编号）。**单独跑一次就推翻了这个假设**——
它照样失败，而且数字从 36 变成 37，说明是**跨运行累加**。

顺着这条线查到 `DefaultPriceOverrideSvc.java:124`：计划名内嵌数据库 `record_id`。
查序列 `last_value` 果然是 37，与观测值精确吻合。
测试断言 `-1`，即假定数据库全新；官方 CI 每次起干净的嵌入式 MySQL，
我们复用常驻 PostgreSQL，而清库用的是 `DELETE`，序列不回退。
`TRUNCATE ... RESTART IDENTITY` 后通过。

**可复用的教训**：
1. **"连跑两次看数字是否变化"是区分隔离问题和状态残留的最便宜实验。**
   数字不变 → 顺序/隔离；数字递增 → 持久化状态没复位。
2. **复用常驻数据库时，第一类会漂移的就是自增序列**，因为清库通常用 `DELETE`。
   断言里出现"从 1 开始的编号"的测试尤其敏感。
3. **断言第三方异常文案的测试，天然依赖 locale**。
   在共享的 `surefireArgLine` 里固定语言环境，比逐个测试打补丁划算。
4. 两个都不是回归，但都**只在把疑点追到具体代码行、并做一次可逆实验之后**才敢这么说。
   "看起来像环境问题"不是结论。

---

### 15. "扩展点都迁过来了"——清点之后发现少了六项

**现象**：全量构建通过、迁移测试通过、OSGi 字眼清零，看起来完成了。
但被追问"目标达成了吗"之后逐项清点，发现缺口比想象的多。

清点方法是**拿上游 `killbill-platform/osgi` 的 31 个类逐个问"它的对应物在哪"**，
而不是问"我写了什么"。结果：

| 缺的 | 后果 |
|---|---|
| `PluginRuntimeModule.platformServices()` 返回 `Map.of()` | 插件拿不到平台的任何 API |
| `OSGIKillbill`（21 个业务 API 门面） | 插件调不了 Core |
| `KillbillEventObservable` + 重试总线处理器 | **`NotificationPluginApi` 全仓零消费者**，插件收不到事件 |
| `OSGIListener` | `START/STOP/RESTART_PLUGIN` 节点命令静默失效 |
| KPM 的安装能力 | `INSTALL/UNINSTALL_PLUGIN` 命令无人处理 |
| 4 个功能 bundle | prometheus / graphite / influxdb / eureka |

**最值得记的一条**：`NotificationPluginApi` 这个接口**编译得好好的、也没有任何测试失败**，
因为它的消费者随 osgi 模块一起被删了。
**一个没有调用方的接口不会以任何形式报错**——
grep "谁引用了它" 返回空的时候，要分清是"本来就没人用"还是"用它的人被我删了"。

**顺带修掉的架构不一致**：`NotificationPluginApi` 当年是唯一不走 registry 的扩展点
（bus → `Observable` 的反射 hack → 插件自带的 dispatcher）。
现在它和其余 12 个一样走 `PluginServiceRegistry`，插件不用再自带 dispatcher。
新实现还比原版多一条：**单个插件抛异常只跳过它自己**，不再让同一事件的其他插件收不到；
但 `NotificationPluginApiRetryException` 照旧上抛，重试语义不变。

**可复用的教训**：
1. **清点删除面要以"被删掉的东西"为清单，不是以"新写的东西"为清单。**
   前者能发现遗漏，后者只能确认已做的。
2. **接口无人调用时不会报错**，因此"删掉一整个模块"之后，
   要专门查一遍它服务过的**上游接口**是否变成了孤儿。

---

### 16. 跨插件边界的第三方类型，作用域写错就是运行时爆炸

**现象**：`metrics-plugin` 把 `metrics-core` 声明为 `compile`，编译通过，
运行时构造 `KillBillCodahaleMetricRegistry` 直接 `ClassCastException`——
而两个类的**全限定名完全相同**。

**根因**：`KillBillCodahaleMetricRegistry` 是 `org.killbill.*`，parent-first，
它的字段类型是**父加载器的** `com.codahale.metrics.MetricRegistry`；
插件 `new` 出来的却是**自己 ClassLoader 的**那一个。名字一样，Class 对象不是同一个。

**解决**：跨边界传递的第三方依赖一律 `provided`，让它从父加载器解析。
这正是 OSGi 时代 `Import-Package` 达到的效果，只是那时写在 bnd 指令里。

**判断方法**（已写进 CLAUDE.md A3）：
这个类型会出现在我注册的服务的方法签名里，或者我会把它传给平台/别的插件吗？

**可复用的教训**：**"两个同名类不兼容"这种报错，永远先看 ClassLoader，不要先看代码。**
A3 原本只列了包前缀白名单，容易让人以为"名单外的都随意"——
实际上名单外但会跨边界的类型，责任转移到了 pom 的作用域上，而 pom 不会替你检查。

---

### 17. 又一次 Python 脚本改 pom 插错了地方

**现象**：给 `eureka-plugin` 补两条依赖，dependency-analyzer 仍报 "used undeclared"。
声明明明在 pom 里。

**根因**：脚本用 `s.replace("    </dependencies>", ...)` 定位插入点，
而这个 pom 有**两个** `</dependencies>`——第一个属于 `<dependencyManagement>`。
两条依赖被插进了 dependencyManagement，只声明了版本，没有真的引入。

**解决**：改用 `s.rindex('    </dependencies>')` 取最后一个。

**可复用的教训**：**pom 里成对的标签几乎都不止一处**
（`<dependencies>`、`<plugins>`、`<configuration>` 都可能同时出现在
`dependencyManagement` / `pluginManagement` / `profiles` 里）。
脚本改 pom 时用 `rindex` 或带上下文的锚点，改完**回读确认落点**。

---

### 18. 两处测试断言了数据库的实现细节，最终选择改测试

issue #12 和 #14 里那两个"MySQL-only"的失败，一直靠排除或手工复位序列绕过。
每跑一次全量都要重来一遍，而且 `TestInternalCallContextFactory` 挂掉会
**连带 15 个下游模块 SKIPPED**——代价远大于那一个测试本身。

最终判断：**这两个测试是错的，不是环境错了**，所以改测试。

| 测试 | 原来 | 现在 |
|---|---|---|
| `TestInternalCallContextFactory` | 裸 INSERT 省略 `grp_id`，靠 MySQL 非严格模式填空串 | 显式给出 `grp_id` |
| `TestEntitlement#testCreateSubscriptionWithOnlyUsageOverrides` | 断言计划名等于 `trebuchet-usage-in-arrear-1` | 断言匹配 `trebuchet-usage-in-arrear-\\d+` |

**为什么这不是"改测试让它通过"**：

- 第一个：`grp_id` 声明为 `NOT NULL` 无默认值，SQL 里省略它在任何严格模式的数据库上都是错的。
  测试依赖的是 MySQL 的一个宽容行为，不是被测代码的契约。
- 第二个：那个 `-1` 是数据库序列值。测试要验证的是"覆盖计划会得到一个派生名"，
  序列恰好是 1 只是"每次跑在全新库上"的副产物。改成断言形状后，
  **它验证的东西一点没少**，只是不再依赖运行环境。

**判别标准**：改测试之前先问——
**这条断言里，有多少是被测代码的契约，有多少是运行环境的巧合？**
如果去掉环境巧合之后断言依然成立，那就是测试写错了。
如果去掉之后什么都不剩，那说明这条断言本来就没在测什么。

**顺带的收益**：`-fae` 下一个失败测试会让全部下游模块 SKIPPED。
修掉这两个之后，`killbill` 子树 19 个模块可以一次跑完，1503 项测试。

---

### 19. 迁移 28 个官方插件：先迁框架，再迁插件

**背景**：所有官方 Java 插件都继承 `killbill-plugin-framework-java` 的 `KillbillActivatorBase`。
逐个迁插件而不先迁框架，等于每个插件从头重写。

**关键数据**：框架 286 个类，**只有 21 个碰 OSGi**。
所以正确顺序是先迁框架（一次性投入），插件才变成小改动。

新写的插件侧 SDK（`org.killbill.billing.plugin.runtime`）只有 4 个类：

| 新 | 替代 | 省掉了什么 |
|---|---|---|
| `KillbillApi` | `OSGIKillbillAPI` | 每个 getter 外面的 `ServiceTracker` 查找 + 重试循环 |
| `PluginConfigProperties` | `OSGIConfigPropertiesService` | 全局属性单一命名空间；现在插件描述符优先 |
| `PluginBase` | `KillbillActivatorBase` | 5 个 ServiceTracker 的开关、`BundleContext` 字段、`super` 调用 |
| `PlatformServiceUnavailableException` | `OSGIServiceNotAvailable` | 语义从"暂时不可用、可重试"变成"这个部署没有这个 API" |

**机械变换沉淀成了脚本**（`migrate_plugin.py` / `port_pom.py` / `gen_plugin_class.py`），
覆盖 12 类替换。值得记的是**踩出来才知道要加的那几类**：

1. **`javax.inject` 也换了命名空间**，不只是 `javax.servlet`。但 `javax.sql` / `javax.annotation` 没换，
   所以只能按前缀列举，不能整体替换。
2. **wrapper 解包调用**：`clock.getClock()` / `dataSource.getDataSource()` 要变成 `clock` / `dataSource`。
3. **`.handleKillbillEvent(` 的调用点**和方法定义都要改。只改定义，测试会在编译期才暴露。
4. **`dataSource` 的替换只在 Plugin 类里成立**：`PluginBase` 把它变成了方法，
   而 DAO 里的同名字段必须原样保留。我第一次没分作用域，把 5 个 DAO 改坏了。
5. **`ServiceTracker<A, A>` 的双泛型**被朴素正则拆成 `A, A x;`，是语法错误。

**最值钱的一条教训**：**插件 pom 里钉死的 `killbill.version` 会盖过 oss-parent。**
`catalog-plugin-test` 写着 `<killbill.version>0.24.0</killbill.version>`，
于是它编译本地源码、链接 2019 年的 jar，症状是运行时 `NoSuchMethodError`
指向一个签名明明对得上的构造器。清掉这类属性覆盖后一次通过。

这与 CLAUDE.md 里"oss-parent 的 5 个版本属性必须与各模块一致"是同一个坑的另一面：
**任何地方对版本属性的局部覆盖，都可能让你编译新代码、运行旧字节码。**

**结果**：12 个插件迁移完成，框架 1312 项测试全绿，插件 70 项。

**明确排除的 2 个**（有证据，不是放弃）：

| 插件 | 证据 |
|---|---|
| `payment-retries-plugin` | oss-parent 0.140.25；`PluginPriorPaymentControlResult(boolean)` / `PluginTenantContext(UUID)` 构造器签名已变 |
| `coupon-plugin-demo` | `EntitlementSpecifier.getQuantity()` / `BaseEntitlementWithAddOnsSpecifier.getBillingEffectiveDate()` 未实现 |

两者都是**对当前 killbill-api 的漂移**，与 OSGi 无关。修它们是"把老插件升级到当前 API"，
是另一件事，所以从 reactor 里移出而不是硬改。

---

### 20. 老插件 pom 里的版本钉死，有三种花样

issue #19 记的是 `killbill.version` 被钉死。补完最后 5 个插件时，同一个坑又以两种新形态出现：

| 形态 | 例子 | 症状 |
|---|---|---|
| 钉死 `killbill-*` 版本 | `catalog-plugin-test` 的 `killbill.version=0.24.0` | `NoSuchMethodError`，签名却对得上 |
| 钉死**三方库**版本 | `vertex` 的 `jackson 2.13.4`、`jakarta.annotation 1.3.5` | `RequireUpperBoundDeps` 报一个你没直接依赖的传递依赖 |
| 钉死 **maven 插件**版本 | `vertex` 的 `maven-dependency-plugin 3.1.2` | `Unsupported class file major version 65`（JDK 21 = 65） |

第三种最有迷惑性：报错说"不支持的 class 文件版本"，看起来像 JDK 配错了，
实际是**构建插件自己太老**，读不懂 JDK 21 编译出来的字节码。

`vertex` 那条 jackson 还带着注释 `<!-- Fixed CVE-2022-42004 -->`——
2022 年钉死是对的，但 oss-parent 现在管的 2.22 早已包含该修复，
钉死反而把它锁在了旧版本上。**安全修复不该靠钉死版本，该靠升级。**

**可复用的教训**：迁移一个老工程时，`grep -n "<version>" pom.xml` 逐条问
"这个版本为什么写在这里"。绝大多数答案是"当年为了解决某个问题"，而那个问题
通常已经被上游更新解决了；留着反而制造新问题。

---

### 21. lombok / 注解处理器版本会静默吞掉编译错误

**现象**：`adyen-plugin` 报 `Compilation failure`，但**没有任何一行错误详情**。
加 `-X`、关 `fork`、单独跑 javac，都拿不到更多信息。

**根因**：它用 lombok 1.18.22（2021 年）+ manifold-preprocessor 2021.1.11。
lombok 通过内部 API 改写 AST，**版本与 JDK 强耦合**；1.18.22 不认识 JDK 21，
注解处理阶段直接崩溃，编译器只报"失败"而没有源码位置。

**解决**：lombok 升到 1.18.34（支持 JDK 21）；manifold 直接删掉——
全仓 grep 没有一处 `#if` 预处理指令，它从头到尾没被用过。

升级后立刻显示出 10 条真实错误（缺字段、旧类名），修完即通过。

**可复用的教训**：**"Compilation failure 但没有错误详情"几乎总是注解处理器的问题**，
不要去猜源码。先看 `annotationProcessorPaths` 里钉的版本是哪一年的。

---

## 四、自己写出来又改掉的（代码质量）

这些没有外部症状，是复查时发现的。记下来是因为它们代表了几类容易反复犯的错误。

| 问题 | 位置 | 为什么是错的 |
|---|---|---|
| 用 `assertNull(null, ...)` 制造 GC 压力的副作用 | 泄漏测试的 `allocateGarbage` | 断言被用作副作用载体，恒真且误导。改成 `volatile` 字段写入，既防 JIT 消除又语义清晰 |
| 无意义的 `static` 块 | `DefaultServiceReference` | `Objects.requireNonNull(常量)` 什么也没保证，纯噪音 |
| `import java.nio.file.Comparator` | `PluginTestRuntime` | 包名写错（应为 `java.util`）。IDE 会挡，纯文本编辑不会 |
| `unregisterAll` 前后两次读 size 计算差值 | `DefaultServiceRegistry` | 两个不相关的快照，并发下计数会漂。改成在 `compute` 内部计数 |
| pom 描述里引用了尚不存在的测试类名 | `lpr-core/pom.xml` | 先写了 `TestArchitecturalConstraints` 的引用才写它。文档承诺要么兑现要么别写 |

---

## 五、方法论：两条被证明有用的做法

### 守卫必须验证过"真的会失败"

写完防护性测试后，**当场故意破坏被保护的代码，确认它确实失败**，再还原。

本项目验证过的：

| 守卫 | 破坏方式 | 结果 |
|---|---|---|
| `TestArchitecturalConstraints` | 给 `lpr-core` 加 guava 依赖 | ✅ 失败，并指出该放到哪个 port 后面 |
| 停止顺序（`TestDefaultPluginLifecycleManager`） | 把 `unregisterAll` 挪到 `plugin.stop()` 之后 | ✅ 失败，打印 `services-STILL-REACHABLE` |

一个永远通过的测试比没有测试更糟——它提供虚假信心。

### 用探针程序验证 JVM 行为，而不是凭记忆

问题 2 就是这么发现的，而且结论**与我原本的认知相反**。20 行的探针程序，比"我记得 JDK 会清空 target"可靠得多。

---

## 附：文档职责划分

避免同一件事记在多处后不一致：

| 文件 | 放什么 |
|---|---|
| `CLAUDE.md` | 规范与约束。每条必须有**违规判据** |
| `docs/analysis/` | 改造前的代码摸底（快照，带行号） |
| `docs/migration/` | 实施方案与 Phase 进度 |
| `docs/development/build-environment.md` | 怎么构建、环境要求 |
| **本文** | **遇到过什么问题、怎么解决的、沉淀到哪里** |
