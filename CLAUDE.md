# CLAUDE.md

Kill Bill fork。**OSGi / Felix 已彻底删除**，插件由自研的轻量级运行时 LPR 加载；官方原本 6 个独立仓库已合并为单 reactor。

现在的形态：`lpr/` 是与 Kill Bill 无关的通用插件运行时，`killbill-platform/plugin-runtime` 是把它接进 Kill Bill 的适配层，`plugins/` 放迁移过来的插件。

这份文件是**工作规范**：不可违反的约束、验证方式、以及踩过就不该再踩的坑。
背景与方案在 [`docs/`](docs/)，不要把它们复制到这里。

---

## 一、不可违反的架构约束

违反这六条中的任何一条，都应该停下来重新设计，而不是"先跑通再说"。

### A1. `lpr-core` 不得依赖任何具体基础设施

LPR 的价值在于**自己拥有插件模型，基础设施可替换**。所有第三方能力必须先定义 SPI，再由独立 adapter 模块实现。

```
lpr-spi                        ← 只有接口，零第三方依赖
  ├── PluginClassLoaderFactory  → lpr-classloader-default   （已实现，默认后端）
  └── DescriptorParser          → lpr-descriptor-yaml       （已实现，SnakeYAML）
```

现存的 adapter 就这两个。EventBus 目前是 `lpr-core` 里的自研实现（几百行，无第三方依赖），
所以还不需要 port；等真要换实现时再抽 `EventBusProvider`。Metrics 同理。

**加新 adapter 时**：先在 `lpr-spi` 定义 port，再开独立模块实现，`lpr-core` 只依赖 port。
不要为了"将来可能要换"提前造空 port——`lpr-spi` 曾因声明了用不到的 `lpr-api` 依赖而被构建拒绝。

**违规判据**：`TestArchitecturalConstraints` 失败。它检查 `lpr-core` 的 classpath 上是否出现 SOFAArk / Guava / Micrometer / SnakeYAML / Jackson / OSGi / Felix / Guice，并在报错时指出该放到哪个 port 后面。

没用 `maven-enforcer` 的 `bannedDependencies` 是**刻意的**：enforcer 经常被 `-Dcheck.skip-enforcer=true` 关掉，而"该生效时是关着的"不算规则。

同一个测试还会检查 `lpr-api` 的方法签名里不出现第三方类型——api 里的每个类型都会成为所有插件编译依赖的 ABI，进去了就再也改不掉。

同理：**LPR 的 API 不得暴露第三方类型**。`PluginContext.eventBus()` 返回的必须是自己定义的 `EventBus`，不是 `com.google.common.eventbus.EventBus`——一旦第三方类型进入插件契约，以后就换不掉了。

### A2. 插件启动早于 Core service，这个顺序不能改

`START_PLUGIN` 属于 `STARTUP_PRE` 序列，**插件在所有 Core service 的 `START_SERVICE` 之前就启动完毕**（见 `platform-api/.../LifecycleHandlerType.LifecycleLevel`）。

因此插件的 `start()` 里**不能立刻调用 Core API 做业务**，必须等 `.../lifecycle/STARTED` 事件。

**为什么不能"顺手修正"**：这个顺序是 Kill Bill 既有语义，改了会让所有存量插件在 Core 未就绪时崩溃。LPR 必须复刻它，包括那个 STARTED 事件。

### A3. 公共 API 必须由 parent ClassLoader 加载

插件的依赖走 child-first，但 `org.killbill.*` / `javax.*` / `jakarta.*` / `org.joda.time` / `org.slf4j` / `org.apache.shiro` 必须 parent-first。

否则 Core 的 `PaymentPluginApi.class` 与插件的 `PaymentPluginApi.class` 不是同一个 Class 对象，运行时 `ClassCastException`。

**但有一条必须记住的例外**：`org.killbill.billing.plugin.` 被从 parent-first 里**挖回 child-first**。
插件的实现代码惯用这个包（`org.killbill.billing.plugin.helloworld`、`.stripe`），它们是插件自己的东西。

推论——**平台侧的插件 API 绝不能放在 `org.killbill.billing.plugin.` 下**，否则会被判成 child-first 而失去共享性。
这就是 killbill-api 里那批类型放在 `org.killbill.billing.runtime.api` 而不是 `...plugin.api` 的原因。

**验证**：`TestClassLoaderIsolation` 断言 API 类型在插件侧与 Core 是同一个 Class 对象；
`TestHelloWorldPluginMigration` 断言插件自己的类确实由插件 ClassLoader 加载。

**第二条推论（这条是踩出来的）**：parent-first 名单只列了包前缀，
但**任何跨插件边界传递的第三方类型**都受同一个约束，而它们不在名单里——
只能靠插件 pom 把该依赖声明为 `provided` 来达到同样效果。

判断方法：这个类型会出现在我注册的服务的方法签名里，或者我会把它传给平台/别的插件吗？
会 → `provided`；不会 → `compile`（child-first，各插件用各自的版本，这正是隔离的价值）。

**违规判据**：运行时 `ClassCastException`，且两个类名**完全一样**。
`metrics-plugin` 就是这样：`metrics-core` 写成 `compile`，
它 `new` 出来的 codahale registry 与平台侧 parent-first 加载的
`KillBillCodahaleMetricRegistry` 期望的类型不是同一个 Class，构造时即失败。

### A4. 资源必须"释放引用"，不能只"关闭"

`ResourceRegistry` 的契约是**丢弃引用**，不是调一下 `shutdown()` 就完事。

**为什么**（JDK 21 实测，非推测）：已终止的 `Thread` 对象**仍然强引用它的 `Runnable`**，因而持有 lambda 捕获的一切。旧版 JDK 的 `Thread.exit()` 会把 `target` 置空，虚拟线程重构后不再这样做。

后果：一个已经 `shutdown()` 但仍被字段引用的 `ExecutorService`，会通过 worker 线程的 task 继续钉住插件 ClassLoader。**插件"卸载"了，类却一个都没回收**，反复热更新就是 metaspace 泄漏。

```
持有已终止的 Thread 对象时, 被回收? false
丢弃 Thread 对象后,        被回收? true
```

**违规判据**：`TestClassLoaderUnloading.testLeakedThreadKeepsClassLoaderAliveUntilItStopsAndIsReleased` 的 Phase 3 失败。
该测试的 Phase 2 反过来也是哨兵——如果哪天它开始失败，说明 JDK 行为变了，这条约束可以放宽。

### A5. 服务注册按 `(pluginId, version)` 作用域，不能只按 pluginId

升级时新旧两个版本**同时加载且共享同一个 pluginId**（这正是"无空窗升级"的实现方式）。若按 pluginId 撤销注册，停掉旧版本会把新版本刚注册的服务一并撤掉——插件显示 ACTIVE，却什么都不提供。

**违规判据**：`TestPluginRuntimeEndToEnd.testUpgradingLeavesNoWindowWithoutAProvider` 与 `testRollbackReturnsToThePreviousVersion` 失败。
（这个 bug 就是被这两个测试抓到的，不是推演出来的。）

### A6. Core 不得依赖任何具体插件

```
        Core
         ▲
    Platform API
         ▲
      Plugin
```

一旦出现 `if (plugin instanceof StripePlugin)` 或 `core → xxx-plugin` 的 pom 依赖，插件架构就已经开始腐化。
业务层只表达需求（capability + selector），由 Router 决定用哪个插件。

---

## 二、构建与验证

### 构建命令

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
/Users/wayne/maven/apache-maven-3.6.3/bin/mvn -B -ff -T 1C clean install \
  -DskipTests=true \
  -Dcheck.skip-spotbugs=true -Dcheck.skip-dependency=true \
  -Dcheck.skip-dependency-scope=true -Dcheck.skip-dependency-versions=true \
  -Dcheck.skip-duplicate-finder=true -Dcheck.skip-enforcer=true -Dcheck.skip-rat=true
```

参考耗时 3 分钟 / 73 模块。**改动跨模块时跑全量，不要只 `-pl` 单模块就宣称通过。**

### 环境坑（每条都实际浪费过时间）

| 坑 | 现象 | 对策 |
|---|---|---|
| **jenv shim 覆盖 JAVA_HOME** | `class file version 55.0 ... only recognizes up to 52.0` | `export JAVA_HOME` 对 shim **无效**。用绝对路径 `/Users/wayne/maven/apache-maven-3.6.3/bin/mvn`，或 `jenv shell 21` |
| **`mvn ... \| tail` 吞掉退出码** | 退出码 0 但实际 `BUILD FAILURE` | 重定向到文件再看，或不接管道 |
| **sortpom 绑在 verify 阶段** | pom.xml 被自动重排 | 手改 pom 后先跑一次构建再提交，避免 diff 噪音。（它只排序不改语义，编辑内容会保留） |
| **`<mirrorOf>*</mirrorOf>`** | sonatype snapshots 被屏蔽 | 需要时改成 `*,!sonatype-nexus-snapshots` |
| **嵌入式 MySQL / Redis 无 aarch64 二进制** | `archive not found: /mysql-Mac_OS_X-aarch64.tar.gz`、`Can't start redis server` | DB 测试改用 dev-infra 的 PostgreSQL（见 build-environment.md 坑 4）；Redis 那个测试只能排除 |
| **reactor 内 jar 的 exclude 不生效** | duplicate-finder 报重复资源，但 jar 里明明没有 | 同一个 reactor 里下游拿到的是兄弟模块的 `target/classes` 而非 jar，凡是作用在"打包产物"上的配置（jar exclude / shade relocation / assembly 过滤）都可能不参与 |
| **`-Dtest` 是替换而非叠加** | 加了 `-Dtest='!SomeTest'` 后冒出全新失败（如 `error instantiating XxxTestSuiteNoDB`） | 纯否定过滤会把 include 模式从 `Test*` 换成"全部"，连测试基类一起扫进来。要写成 `-Dtest='Test*,!SomeTest'`，或干脆用 `-rf :<下游模块>` 续跑 |
| **JVM locale 是中文** | `expected [Invalid content...] but found [发现了以元素...]` | 断言 JDK/三方库异常文案的测试必然挂。argLine 固定 `-Duser.language=en -Duser.country=US` |
| **PG 序列不随 `DELETE` 回退** | `expected [xxx-1] but found [xxx-37]`，且**每跑一次加一** | 复用常驻库的副作用（官方 CI 每次起干净的嵌入式 MySQL）。`TRUNCATE ... RESTART IDENTITY` 复位 |

### 测试

```bash
mvn test                      # fast
mvn test -Ptravis             # fast,slow
mvn test -Ppostgresql         # fast,slow,postgresql
mvn test -Pintegration-mysql  # fast,slow,mysql,integration
```

本地基础设施（OrbStack `dev-infra` 分组）：PostgreSQL 16 `localhost:5432` (`dev`/`dev`)、Redis 7 `localhost:6379`。

本机跑 DB 测试的标准 argLine（缺任何一段都会产生**看起来像回归的失败**）：

```bash
ARGLINE="-Xmx1024m -Duser.language=en -Duser.country=US \
  --add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.io=ALL-UNNAMED \
  --add-opens java.base/java.util=ALL-UNNAMED --add-opens java.base/java.base=ALL-UNNAMED \
  -Dorg.killbill.billing.dbi.test.postgresql=true \
  -Dorg.killbill.billing.dbi.test.useLocalDb=true \
  -Dorg.killbill.billing.dbi.test.localDb.database=killbill \
  -Dorg.killbill.billing.dbi.test.localDb.username=dev \
  -Dorg.killbill.billing.dbi.test.localDb.password=dev"
mvn -B -fae test -DsurefireArgLine="$ARGLINE"
```

`-D` 直接传给 mvn **到不了 surefire 的 fork**，必须走 `-DsurefireArgLine`；
而一旦覆盖它，就得把上面四个 `--add-opens` 一起带上（它们本来由 `javac17-release` profile 提供）。

### 判断一个失败是不是回归

改造期最贵的错误是**把环境问题当回归去改代码**，或反过来。判别顺序：

1. **未改动的模块能否复现？** 能 → 不是回归。
2. **连跑两次，数字是否变化？** 递增 → 持久化状态没复位；不变 → 才可能是顺序/隔离。
3. **对照组只许差一个变量。** 曾经为验证"是不是过滤器造成的"而单跑一个模块，
   却顺手漏了 PG 参数，得到一个无关的失败，一度证伪了正确的假设。
4. 追到**具体代码行**再下结论。"看起来像环境问题"不是结论。

### 声称"完成"的门槛

- 跨模块改动 → 全量构建通过
- 改了业务逻辑 → 相关模块 `mvn test` 通过
- 改了插件运行时 → `lpr-testkit` 的隔离性 + 泄漏性测试通过
- **测试失败就说失败，附上输出**。不要说"应该没问题"

---

## 三、改代码的约定

### 目录与坐标

- 保留 `org.kill-bill.*` 的 groupId 和 Java 包名，**不要改包名**
- 三个 groupId 并存且**不能统一**：`org.kill-bill.billing`（core/platform）、`org.kill-bill.billing.plugin`（plugin-api）、`org.kill-bill.commons`（commons）
- 新代码放 `lpr/`，不要塞进 `killbill-platform/osgi`（那是待删除的）

### 两个会咬人的命名不一致

| 目录 | artifactId |
|---|---|
| `killbill/api` | **`killbill-internal-api`**（不是 `killbill-api`！那是另一个仓库） |
| `killbill-platform/osgi-bundles` | `killbill-platform-osgi-all-bundles` |

### 改 pom 时

- 顶级 `pom.xml` 是**纯 aggregator**，不是任何模块的 parent。加模块只加 `<modules>`，别加 `dependencyManagement`
- 依赖版本统一在 `killbill-oss-parent` 的 `dependencyManagement` 里管，子模块 pom 不写 `<version>`
- `killbill-oss-parent` 里那 5 个 `killbill-*.version` 属性**必须与各模块根 pom 的 `<version>` 保持一致**，否则会静默从 Maven Central 拉旧 jar（编译本地源码、链接上个 release，最难排查的一类问题）
- `<modules>` 顺序即依赖拓扑，不要重排

### 不要把 OSGi 加回来

OSGi 已删除干净：141 个文件 / 16408 行、3 个模块树、全部 Felix 与 `org.osgi` 依赖、
`maven-bundle-plugin` 与 bnd 指令、`packaging=bundle`（现在 0 个模块用）。

`TestArchitecturalConstraints` 会在 `lpr-core` 看到 `org.osgi` 或 Felix 时失败。
其余地方靠约定：新增插件能力时，先问"LPR 是不是已经有这个 port"，而不是找 OSGi 里对应的东西怎么做。

替代关系的完整对照表在 [`docs/migration/osgi-to-lpr-plan.md`](docs/migration/osgi-to-lpr-plan.md) 附录四。

### 删代码前，确认"0 处引用"的统计范围

这条是踩出来的：分析报告写着某方法"main 源码 0 调用"，但那次统计只覆盖了 killbill 业务核心，
不含 `killbill-platform`——删掉后 platform 编译失败。

**跨模块删 API 前，按全仓再查一次**，别复用别处的统计结论。

### 插件 pom 不要钉死 `killbill-*` 版本属性

`killbill-oss-parent` 已经把这些版本管好了。插件 pom 里再写一遍
（`<killbill.version>0.24.0</killbill.version>`）会**盖过它**，于是
**编译本地源码、链接上游发布的旧 jar**。

**违规判据**：运行时 `NoSuchMethodError` 或 `NoClassDefFoundError`，
而报错指向的签名在源码里明明对得上。`catalog-plugin-test` 就是这样——
它钉了 `0.24.0`，`VersionedCatalogLoader` 的构造器参数类型早已改名。

查一遍：

```bash
grep -l "killbill.*\.version>" plugins/*/pom.xml
```

这与「改 pom 时」里 oss-parent 那 5 个版本属性是同一个坑的两面：
**任何对版本属性的局部覆盖，都可能让你编译新代码、运行旧字节码。**

### 删模块前，先列它 `bind()` 了什么

删掉实现类会**编译**报错，删掉 Guice 绑定只会在**运行时**报错，而且报错位置离原因很远。

删掉 `killbill-platform/osgi` 时漏掉了它绑定的 `PluginsInfoApi`，
代价是 beatrix 全部集成测试在注入器创建时挂掉——而 Guice 的 `Requested by`
指向的是**请求方**（`NodesModule`），不是缺失方。顺着报错找只会看到无辜的模块。

删模块前跑一遍：

```bash
grep -nE "bind\(|@Provides" <被删模块>/**/glue/*.java
```

逐条确认新模块接上了，或者确认这个绑定确实不该再存在。

正确的追查方向也是反的：不要问"我改坏了什么"，要问
**"这个绑定原本由谁提供"**——`grep -rn "bind(XxxApi.class)"` 全仓，答案通常一眼可见。

---

## 四、代码风格

沿用 Kill Bill 既有风格，**写出来的代码要像周围的代码**。具体地：

- **不可变优先**：返回新对象而不是原地修改。Kill Bill 大量用 `final` 参数，跟上
- **文件保持聚焦**：200–400 行常态，800 行是上限。超了就拆
- **函数 < 50 行，嵌套 < 4 层**
- **错误显式处理**：不吞异常。Kill Bill 的插件调用点普遍是"插件挂了记 warn 然后跳过"，改动时保持这个容错语义
- **边界校验**：插件返回的数据是外部输入，必须校验（参考 `InvoicePluginDispatcher.validateAndSanitizeInvoiceItemFromPlugin`）
- **不硬编码**：配置走 skife-config 接口（参考 `OSGIConfig`），不要 `System.getProperty`

新写 LPR 代码时额外注意：

- **并发安全**：`PluginRegistry` / `ServiceRegistry` / `PluginState` / `EventBus` 都会被并发访问。状态迁移用 `AtomicReference<PluginState>` + CAS，保证 START 不会并发执行两次
- **资源必须托管**：插件创建的 executor / subscription / connection 一律进 `ResourceRegistry`。这不是洁癖——**没托管的资源会让 ClassLoader 无法 GC**，插件卸载就是假的

---

## 五、工作方式

### 按风险排序，不按模块顺序

设计文档 v1 第 58 节明确要求这一点。**ClassLoader 隔离是最高风险项，必须最先验证**，不要先写一堆 Manager/Registry 再回头发现隔离方案不成立。

### 两个硬门禁

| 门禁 | 不通过的含义 |
|---|---|
| Phase 0 全量构建 | 没有工作基线，后面所有改动都无法验证 |
| Phase 2 ClassLoader PoC | 技术路线不成立，要换方案而不是继续往上堆 |

### 先收敛再扩散

改造插件调用面时的正确顺序：先在 `lpr-api` 定义好新接口 → 让 6 个业务模块只改 import → 把真正的复杂度收敛到平台侧 8 个实现点。

**不要**一边定义接口一边改调用方，会来回返工。

### 守卫必须验证过"真的会失败"

写完一个用来防止某类错误的测试（架构约束、顺序约束、不变量），**当场故意破坏被保护的代码，确认它确实失败**，然后还原。

**为什么**：一个永远通过的测试比没有测试更糟——它提供的是虚假信心。这个仓库里已有两个守卫是这样验证过的：

| 守卫 | 验证方式 |
|---|---|
| `TestArchitecturalConstraints` | 给 `lpr-core` 加 guava 依赖 → 确认失败并给出可操作提示 |
| 停止顺序（`TestDefaultPluginLifecycleManager`） | 把 `unregisterAll` 挪到 `plugin.stop()` 之后 → 确认失败 |
| 装配契约（`TestPluginRuntimeModule`） | 摘掉 `PluginsInfoApi` 绑定 → 0.6 秒内以和 beatrix 相同的 Guice 报错失败 |

**给装配层写守卫时，断言的对象是"外部要什么"，不是"我有什么"**——
一个模块最容易漏的，正是自己不用、只供别人用的绑定。

同理，涉及 JVM 行为的假设（GC、ClassLoader、线程引用）**要写探针程序实测，不要凭记忆断言**——A4 那条约束就是这么发现的，与"我以为的" JDK 行为相反。

### 大改动前先读分析报告

`docs/analysis/` 里的三份报告都标注了文件路径和行号。动手前先查，比重新 grep 一遍快，也不容易漏。

---

## 六、飞轮：怎么维护这份文件

这份文件的价值在于**让下一次改动比这一次更省力**。它会过时，所以需要主动维护。

### 什么时候往这里加东西

只加满足以下任一条的内容：

1. **踩了坑，且下次还会踩** → 加到「环境坑」表格，写清楚"现象 → 对策"
2. **发现了一个不变量，破坏它会出事** → 加到「架构约束」，**必须写违规判据**（怎么发现自己违规了）
3. **定了一个约定，不写下来会有分歧** → 加到「改代码的约定」
4. **某个操作有非显然的正确顺序** → 加到「工作方式」

### 什么不该加到这里

- 进度、已完成事项、变更记录 → 那是 `docs/migration/osgi-to-lpr-plan.md` 的事
- 排查过程、"当时怎么发现的" → 那是 `docs/development/issue-log.md`（问题账本）的事。
  只有从中提炼出的**约束**才进这里，且必须带违规判据
- 背景、动机、调研结论 → 那是 `docs/` 的事
- 一次性的信息（某个 bug 的排查过程）→ 不要留，除非它揭示了一个不变量
- 泛泛的最佳实践（"要写好代码"）→ 没有判据就没有价值

### 加的时候

- **写判据，不写感受**。"避免 ClassLoader 泄漏" ❌ → "资源不进 ResourceRegistry，ClassLoader 就无法 GC，`lpr-testkit` 的泄漏性测试会挂" ✅
- **删掉过时的**。约束消失了就删，不要留着"历史上曾经"
- 保持这份文件**能一口气读完**。它长到需要目录跳转的时候，说明该往 `docs/` 分流了

### 已分流出去的内容

以下四项曾列为空白，现在都在
[`docs/development/plugin-development.md`](docs/development/plugin-development.md)：
`plugin.yaml` 字段与校验规则、新插件开发规范、`lpr-testkit` 用法、热更新与回滚的操作约定。

这份文件只保留**约束与判据**；"怎么做"归 `docs/`。
