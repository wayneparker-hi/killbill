# AGENTS.md

## 项目与目录

Kill Bill 计费平台 fork，单 Maven reactor；OSGi/Felix 已移除，禁止重新引入。

| 路径 | 职责 |
|---|---|
| `killbill/` | 账户、目录、订阅、权益、用量、发票、支付、逾期及 REST API |
| `killbill-api/`、`killbill-plugin-api/` | 平台公共 API、业务插件接口 |
| `killbill-commons/` | 公共基础库 |
| `lpr/` | 与 Kill Bill 无关的通用插件运行时 |
| `killbill-platform/plugin-runtime/` | LPR 与 Kill Bill 的适配及装配 |
| `killbill-plugin-framework/`、`plugins/` | 插件 SDK、具体插件 |
| `killbill-oss-parent/` | Parent POM、依赖版本与构建规约 |

## 架构约束

| 编号 | 必须遵守 | 验证依据 |
|---|---|---|
| A1 | `lpr-core` 只依赖 port，不依赖或内置基础设施实现；插件契约放 `lpr-api`，运行时 SPI 放 `lpr-spi`，实现放独立 adapter；LPR 公共 API 不暴露第三方类型 | `TestArchitecturalConstraints` |
| A2 | `START_PLUGIN` 必须早于 Core 的 `START_SERVICE`；插件 `start()` 中的 Core 业务调用必须等待 `.../lifecycle/STARTED` 事件 | `LifecycleHandlerType`、生命周期测试 |
| A3 | 公共 API 必须由 parent ClassLoader 加载；插件实现及私有依赖 child-first；跨边界传递的第三方类型在插件 POM 中声明为 `provided` | `TestClassLoaderIsolation`、`TestHelloWorldPluginMigration` |
| A4 | 插件资源必须进 `ResourceRegistry`；停止顺序：撤销服务 → `Plugin.stop()` → 释放资源 → 关闭 ClassLoader → 丢弃引用；关闭后仍须清除强引用 | `TestDefaultPluginLifecycleManager`、`TestClassLoaderUnloading` |
| A5 | 服务注册与撤销按 `(pluginId, version)` 作用域；升级先启动新版本再切换、停旧版本，撤销旧版本不得影响新版本 | `TestPluginRuntimeEndToEnd` 的升级与回滚测试 |
| A6 | Core/Platform 不依赖具体插件，不按插件实现类型分支；业务只表达 capability + selector，由 Router/Registry 选择实现 | 全仓引用检查、POM 依赖检查 |

A3 的 parent-first 前缀包括 `org.killbill.`、`javax.`、`jakarta.`、`org.joda.time.`、`org.slf4j.`、`org.apache.shiro.`。
例外：`org.killbill.billing.plugin.` 必须 child-first；平台共享 API 使用 `org.killbill.billing.runtime.api`，不得放入该例外包。

## 修改规则

- 保留现有 Java 包名及三类 groupId：`org.kill-bill.billing`、`org.kill-bill.billing.plugin`、`org.kill-bill.commons`。
- 通用运行时能力放 `lpr/`，Kill Bill 适配放 `killbill-platform/plugin-runtime/`，具体插件放 `plugins/`。
- 根 `pom.xml` 仅作 aggregator，不作 parent、不加 `dependencyManagement`；保持 `<modules>` 拓扑顺序。
- 依赖版本由 `killbill-oss-parent` 统一管理，受管依赖不在子模块重复指定版本；插件不得覆盖 `killbill-*.version`。
- Parent 中五个核心版本属性须与各模块根 POM 版本一致；注意 `killbill/api` 的 artifactId 是 `killbill-internal-api`。
- 删除 API 前检查全仓引用；删除模块或装配代码前列出 `bind()` / `@Provides`，逐项确认替代绑定或移除依据。
- 沿用周边风格，优先不可变对象与 `final` 参数；文件不超过 800 行，函数少于 50 行，嵌套少于 4 层。
- 校验插件返回数据；异常显式处理，保留插件失败时记录 warn 并跳过的既有容错语义。
- 平台配置使用 skife-config 接口（如 `PluginRuntimeConfig`），不硬编码或直接读取 `System.getProperty`。
- Registry、EventBus 与生命周期必须并发安全；状态迁移使用 `AtomicReference<PluginState>` + CAS，防止重复启动。

## 工作流程与验证

- 修改前检查工作区，保留已有未提交改动；大改动先读相关设计、规格和分析报告，并核实源码现状。
- 按风险推进：先验证 ClassLoader 隔离，再扩展运行时；跨模块接口先收敛契约，再修改调用方。
- 需求级行为变更先更新 `spec/`；在 `changes/` 维护提案与任务，Scenario 对应可执行测试，完成后同步规格并归档。
- 新增守卫测试必须临时破坏被保护条件，确认测试失败后还原；JVM/GC/线程引用假设用探针或测试实测。
- 大改动前建立全量构建基线；跨模块代码/POM 改动须全量构建，不能仅凭单模块构建宣称通过。
- 业务逻辑变更须运行相关模块测试；插件运行时变更须验证架构约束、隔离性、泄漏性及 `lpr-testkit` 端到端测试。
- 失败须报告命令、输出与影响；判断回归时保持对照组仅差一个变量，核实环境及持久化状态，并定位具体代码。

构建使用 JDK 21 和真实 Maven 路径，避免 jenv shim 覆盖 `JAVA_HOME`：

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
/Users/wayne/maven/apache-maven-3.6.3/bin/mvn -B -ff -T 1C clean install \
  -DskipTests=true \
  -Dcheck.skip-spotbugs=true -Dcheck.skip-dependency=true \
  -Dcheck.skip-dependency-scope=true -Dcheck.skip-dependency-versions=true \
  -Dcheck.skip-duplicate-finder=true -Dcheck.skip-enforcer=true -Dcheck.skip-rat=true
```

此命令只验证构建，不代表测试通过。测试默认 `mvn test`（fast），其他分组及本机 DB 参数见构建环境文档。
DB 测试参数必须经 `-DsurefireArgLine` 传入 fork，保留四个 `--add-opens` 并固定英文 locale；共用数据库时不并行。
不要用管道吞掉 Maven 退出码；报告检查结果时明确跳过项。

## 文档入口与维护

| 内容 | 位置 |
|---|---|
| 系统规格、工程约束 | [spec/](spec/README.md)、[constitution.md](spec/constitution.md) |
| 设计与历史分析 | [LPR 设计](docs/design/lightweight-plugin-runtime.md)、[docs/analysis/](docs/analysis/) |
| 构建、测试参数与环境排查 | [build-environment.md](docs/development/build-environment.md) |
| 插件开发、描述符、TestKit、升级与回滚 | [plugin-development.md](docs/development/plugin-development.md) |
| 使用方式、文档索引 | [usage.md](docs/usage.md)、[docs/README.md](docs/README.md) |

- 本文件只保留通用约束、操作顺序、验证门槛与文档入口；使用短条目或表格，避免重复与叙述性案例。
- 背景与方案放 `docs/`，排查记录放 `docs/development/issue-log.md`，变更进度放 `changes/`，迁移进度放 `docs/migration/`。
- 新增规则必须可执行、可验证；规则失效即删除。`CLAUDE.md` 仅引用本文件，不维护副本。
